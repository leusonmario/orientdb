package com.orientechnologies.orient.core.storage.impl.local.paginated.wal;

import com.orientechnologies.common.directmemory.OByteBufferPool;
import com.orientechnologies.common.exception.OException;
import com.orientechnologies.common.io.OFileUtils;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.common.serialization.types.OIntegerSerializer;
import com.orientechnologies.common.serialization.types.OLongSerializer;
import com.orientechnologies.common.util.OPair;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.exception.OStorageException;
import com.orientechnologies.orient.core.storage.OStorageAbstract;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

final class OLogSegment implements Comparable<OLogSegment> {
  private final OByteBufferPool byteBufferPool = OByteBufferPool.instance();
  private ODiskWriteAheadLog writeAheadLog;

  /**
   * File which contains WAL segment data.
   * It is <code>null</code> by default and initialized on request.
   * <p>
   * When file is requested and if this file is not file of active WAL segment
   * then timer which will close file if it is not accessed any more in {@link #fileTTL} seconds will
   * be started.
   * <p>
   * This field is not supposed to be accessed directly please use {@link #getRndFile()} instead.
   *
   * @see #closer
   * @see #fileTTL
   */
  private RandomAccessFile rndFile;

  /**
   * Lock which protects {@link #rndFile} access. Any time you call {@link #getRndFile()} you should also
   * acquire this lock.
   */
  private final Lock fileLock = new ReentrantLock();

  /**
   * Flag which indicates if auto close timer is started.
   * This flag is used to guarantee that one and only one instance of auto close timer is active at any moment.
   *
   * @see #rndFile
   */
  private final AtomicBoolean autoCloseInProgress = new AtomicBoolean();

  /**
   * Flag which when is set will prevent auto close timer to close of file. But timer itself
   * will not be stopped.
   *
   * @see #rndFile
   */
  private volatile boolean preventAutoClose = false;

  private final File file;

  /**
   * Flag which indicates that file was accessed inside of {@link #fileTTL} which means that file will not be accessed
   * at least inside of next {@link #fileTTL} interval.
   */
  private volatile boolean closeNextTime;

  private volatile long writtenUpTo;

  /**
   * If {@link #rndFile} will not be accessed inside of this interval (in seconds) it will be closed by timer.
   *
   * @see #rndFile
   */
  private final int fileTTL;

  /**
   * Scheduler which will be used to start timer which will close file if last one will not be accessed inside of
   * {@link #fileTTL} in seconds.
   *
   * @see #rndFile
   */
  private final ScheduledExecutorService closer = Executors.newScheduledThreadPool(1, new ThreadFactory() {
    @Override
    public Thread newThread(Runnable r) {
      final Thread thread = new Thread(OStorageAbstract.storageThreadGroup, r);
      thread.setDaemon(true);
      thread.setName("WAL Closer Task (" + writeAheadLog.getStorage().getName() + ")");
      return thread;
    }
  });

  private final long order;
  private final int  maxPagesCacheSize;
  protected final  Lock             cacheLock = new ReentrantLock();
  private volatile List<OLogRecord> logCache  = new ArrayList<OLogRecord>();

  private final ScheduledExecutorService commitExecutor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
    @Override
    public Thread newThread(Runnable r) {
      final Thread thread = new Thread(OStorageAbstract.storageThreadGroup, r);
      thread.setDaemon(true);
      thread.setName("OrientDB WAL Flush Task (" + writeAheadLog.getStorage().getName() + ")");
      return thread;
    }
  });
  private volatile long    filledUpTo;
  private          boolean closed;
  private OLogSequenceNumber last = null;
  private OLogSequenceNumber pendingLSNToFlush;

  private volatile boolean flushNewData = true;

  private WeakReference<OPair<OLogSequenceNumber, byte[]>> lastReadRecord = new WeakReference<OPair<OLogSequenceNumber, byte[]>>(
      null);

  private final class FlushTask implements Runnable {

    private long lastFSyncTime = -1;
    private long fsyncInterval = writeAheadLog.getCommitDelay() * 1000000L;

    private long             startPageIndex = -1;
    private List<ByteBuffer> pagesToFlush   = new ArrayList<ByteBuffer>();

    private OLogSequenceNumber lastSerializedLSN = null;

    private boolean pendingFSync = false;

    private final boolean forceFSync;

    private long serializedUpTo = 0;

    private FlushTask(boolean forceFSync) {
      this.forceFSync = forceFSync;
    }

    @Override
    public void run() {
      try {
        serializePendingRecords();

        flushAndSyncPages(forceFSync);
      } catch (Throwable e) {
        File parent = file.getParentFile();

        final long freeSpace = parent.getFreeSpace();
        final long usableSpace = parent.getUsableSpace();

        OLogManager.instance()
            .error(this, "Error during WAL background flush, free space " + freeSpace + ", usable space " + usableSpace, e);

      }
    }

    private void serializePendingRecords() throws IOException {
      if (!flushNewData)
        return;

      flushNewData = false;

      List<OLogRecord> toFlush;
      try {
        cacheLock.lock();
        if (logCache.isEmpty())
          return;

        toFlush = logCache;
        logCache = new ArrayList<OLogRecord>();
      } finally {
        cacheLock.unlock();
      }

      ByteBuffer buffer;
      boolean fetchedFromList = false;

      OLogRecord first = toFlush.get(0);
      long curPageIndex = first.writeFrom / OWALPage.PAGE_SIZE;

      if (pagesToFlush.isEmpty()) {
        startPageIndex = curPageIndex;

        fileLock.lock();
        try {
          buffer = ByteBuffer.allocate(OWALPage.PAGE_SIZE).order(ByteOrder.nativeOrder());
          final RandomAccessFile rndFile = getRndFile();

          long pagesCount = rndFile.length() / OWALPage.PAGE_SIZE;
          if (pagesCount > curPageIndex) {
            FileChannel channel = rndFile.getChannel();
            channel.read(buffer, curPageIndex * OWALPage.PAGE_SIZE);
          }
        } finally {
          fileLock.unlock();
        }
      } else {
        int index = (int) (curPageIndex - startPageIndex);

        if (index < pagesToFlush.size()) {
          assert index == pagesToFlush.size() - 1;

          buffer = pagesToFlush.get(index);
          fetchedFromList = true;
        } else {
          buffer = ByteBuffer.allocate(OWALPage.PAGE_SIZE).order(ByteOrder.nativeOrder());
        }
      }

      OLogSequenceNumber lsn = null;
      boolean lastToFlush = false;

      for (OLogRecord log : toFlush) {
        lsn = new OLogSequenceNumber(order, log.writeFrom);
        int pos = (int) (log.writeFrom % OWALPage.PAGE_SIZE);
        int written = 0;

        while (written < log.record.length) {
          lastToFlush = true;

          int pageFreeSpace = OWALPage.calculateRecordSize(OWALPage.PAGE_SIZE - pos);
          int contentLength = Math.min(pageFreeSpace, (log.record.length - written));
          int fromRecord = written;
          written += contentLength;

          pos = writeContentInPage(buffer, pos, log.record, written == log.record.length, fromRecord, contentLength);
          if (OWALPage.PAGE_SIZE - pos < OWALPage.MIN_RECORD_SIZE) {
            preparePageForFlush(buffer);

            if (!fetchedFromList) {
              pagesToFlush.add(buffer);
            }

            buffer = ByteBuffer.allocate(OWALPage.PAGE_SIZE).order(ByteOrder.nativeOrder());
            lastToFlush = false;
            fetchedFromList = false;

            pos = OWALPage.RECORDS_OFFSET;
          }
        }

        serializedUpTo = log.writeTo;
      }

      if (lastToFlush) {
        preparePageForFlush(buffer);

        if (!fetchedFromList) {
          pagesToFlush.add(buffer);
        }
      }

      if (lsn != null)
        lastSerializedLSN = lsn;
    }

    /**
     * Write the content in the page and return the new page cursor position.
     *
     * @param pageContent   buffer of the page to be filled
     * @param posInPage     position in the page where to write
     * @param log           content to write to the page
     * @param isLast        flag to mark if is last portion of the record
     * @param fromRecord    the start of the portion of the record to write in this page
     * @param contentLength the length of the portion of the record to write in this page
     * @return the new page cursor  position after this write.
     */
    private int writeContentInPage(ByteBuffer pageContent, int posInPage, byte[] log, boolean isLast, int fromRecord,
        int contentLength) {
      pageContent.put(posInPage, !isLast ? (byte) 1 : 0);
      pageContent.put(posInPage + 1, isLast ? (byte) 1 : 0);
      pageContent.putInt(posInPage + 2, contentLength);
      pageContent.position(posInPage + OIntegerSerializer.INT_SIZE + 2);
      pageContent.put(log, fromRecord, contentLength);

      posInPage += OWALPage.calculateSerializedSize(contentLength);

      pageContent.putInt(OWALPage.FREE_SPACE_OFFSET, OWALPage.PAGE_SIZE - posInPage);
      return posInPage;
    }

    private void preparePageForFlush(ByteBuffer content) throws IOException {
      content.putLong(OWALPage.MAGIC_NUMBER_OFFSET, OWALPage.MAGIC_NUMBER);

      final byte[] data = new byte[OWALPage.PAGE_SIZE - OWALPage.MAGIC_NUMBER_OFFSET];
      content.position(OWALPage.MAGIC_NUMBER_OFFSET);
      content.get(data);

      CRC32 crc32 = new CRC32();
      crc32.update(data);
      content.putInt(OWALPage.CRC_OFFSET, (int) crc32.getValue());
    }

    private void flushAndSyncPages(boolean forceFSync) throws IOException {
      final long fsyncTs = System.nanoTime();

      boolean pending = pagesToFlush.size() * OWALPage.PAGE_SIZE >= 16 * 1024 * 1024;
      boolean fsync =
          forceFSync || ((pendingFSync || pending) && ((fsyncTs - lastFSyncTime) >= fsyncInterval || lastFSyncTime == -1));

      fileLock.lock();
      try {
        if (pending || fsync) {
          final RandomAccessFile rndFile = getRndFile();
          final FileChannel channel = rndFile.getChannel();

          if (!pagesToFlush.isEmpty()) {

            for (ByteBuffer buffer : pagesToFlush) {
              buffer.position(0);
            }

            channel.position(startPageIndex * OWALPage.PAGE_SIZE);
            channel.write(pagesToFlush.toArray(new ByteBuffer[0]));

            pagesToFlush.clear();

            writtenUpTo = serializedUpTo;
            startPageIndex = -1;
            pendingFSync = true;

            writeAheadLog.checkFreeSpace();
          }


          if (fsync) {
            assert pagesToFlush.isEmpty();

            channel.force(false);

            if (lastSerializedLSN != null) {
              writeAheadLog.setFlushedLsn(lastSerializedLSN);
              lastSerializedLSN = null;
            }

            lastFSyncTime = fsyncTs;
            pendingFSync = false;
          }
        }
      } finally {
        fileLock.unlock();
      }
    }
  }

  OLogSegment(ODiskWriteAheadLog writeAheadLog, File file, int fileTTL, int maxPagesCacheSize) throws IOException {
    this.writeAheadLog = writeAheadLog;
    this.file = file;
    this.fileTTL = fileTTL;
    this.maxPagesCacheSize = maxPagesCacheSize;

    order = extractOrder(file.getName());
    closed = false;
  }

  public void startFlush() {
    if (writeAheadLog.getCommitDelay() > 0) {
      commitExecutor.scheduleAtFixedRate(new FlushTask(false), 1, 1, TimeUnit.MILLISECONDS);

      //if WAL segment is active (all content is written in this segment) we should not try to close it after TTL.
      preventAutoClose = true;
    }
  }

  public void stopFlush(boolean flush) {
    if (flush)
      flush();

    if (!commitExecutor.isShutdown()) {
      commitExecutor.shutdown();
      try {
        if (!commitExecutor.awaitTermination(OGlobalConfiguration.WAL_SHUTDOWN_TIMEOUT.getValueAsInteger(), TimeUnit.MILLISECONDS))
          throw new OStorageException("WAL flush task for '" + getPath() + "' segment cannot be stopped");

      } catch (InterruptedException e) {
        OLogManager.instance().error(this, "Cannot shutdown background WAL commit thread");
      }
    }

    //segment is not active any more we should start file auto close
    preventAutoClose = false;
  }

  /**
   * Returns active instance of file which is associated with given WAL segment
   * Call of this method should always be protected by {@link #fileLock}.
   *
   * @return Active instance of file which is associated with given WAL segment
   */
  private RandomAccessFile getRndFile() throws IOException {
    if (rndFile == null) {
      rndFile = new RandomAccessFile(file, "rw");
      scheduleFileAutoClose();
    } else {
      closeNextTime = false;
    }

    return rndFile;
  }

  /**
   * Start timer thread which will auto close file if it is not accesses during {@link #fileTTL} seconds.
   * If file is already closed timer thread will be terminate itself till it will not be started again by
   * {@link #getRndFile()} call.
   */
  private void scheduleFileAutoClose() {
    if (!autoCloseInProgress.get() && autoCloseInProgress.compareAndSet(false, true)) {
      closeNextTime = true;
      final FileCloser task = new FileCloser();
      task.self = closer.scheduleWithFixedDelay(task, fileTTL, fileTTL, TimeUnit.SECONDS);

    }
  }

  public long getOrder() {
    return order;
  }

  public void init() throws IOException {
    selfCheck();

    initPageCache();

    last = new OLogSequenceNumber(order, filledUpTo - 1);
  }

  @Override
  public int compareTo(OLogSegment other) {
    final long otherOrder = other.order;

    if (order > otherOrder)
      return 1;
    else if (order < otherOrder)
      return -1;

    return 0;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o)
      return true;
    if (o == null || getClass() != o.getClass())
      return false;

    OLogSegment that = (OLogSegment) o;

    return order == that.order;

  }

  @Override
  public int hashCode() {
    return (int) (order ^ (order >>> 32));
  }

  public long filledUpTo() throws IOException {
    return filledUpTo;
  }

  public OLogSequenceNumber begin() throws IOException {
    if (!logCache.isEmpty())
      return new OLogSequenceNumber(order, OWALPage.RECORDS_OFFSET);

    fileLock.lock();
    try {
      final RandomAccessFile rndFile = getRndFile();

      if (rndFile.length() > 0)
        return new OLogSequenceNumber(order, OWALPage.RECORDS_OFFSET);

    } finally {
      fileLock.unlock();
    }

    return null;
  }

  public OLogSequenceNumber end() {
    return last;
  }

  public void delete(boolean flush) throws IOException {
    close(flush);

    boolean deleted = OFileUtils.delete(file);
    int retryCount = 0;

    while (!deleted) {
      deleted = OFileUtils.delete(file);
      retryCount++;

      if (retryCount > 10)
        throw new IOException("Cannot delete file. Retry limit exceeded. (" + retryCount + ")");
    }
  }

  public String getPath() {
    return file.getAbsolutePath();
  }

  public static class OLogRecord {
    public final byte[] record;
    public final long   writeFrom;
    public final long   writeTo;

    public OLogRecord(byte[] record, long writeFrom, long writeTo) {
      this.record = record;
      this.writeFrom = writeFrom;
      this.writeTo = writeTo;
    }
  }

  public static OLogRecord generateLogRecord(final long starting, final byte[] record) {
    long from = starting;
    long length = record.length;
    long resultSize;
    int freePageSpace = OWALPage.PAGE_SIZE - (int) Math.max(starting % OWALPage.PAGE_SIZE, OWALPage.RECORDS_OFFSET);
    int inPage = OWALPage.calculateRecordSize(freePageSpace);
    //the record fit in the current page
    if (inPage >= length) {
      resultSize = OWALPage.calculateSerializedSize((int) length);
      if (from % OWALPage.PAGE_SIZE == 0)
        from += OWALPage.RECORDS_OFFSET;
      return new OLogRecord(record, from, from + resultSize);
    } else {
      if (inPage > 0) {
        //space left in the current page, take it
        length -= inPage;
        resultSize = freePageSpace;
        if (from % OWALPage.PAGE_SIZE == 0)
          from += OWALPage.RECORDS_OFFSET;
      } else {
        //no space left, start from a new one.
        from = starting + freePageSpace + OWALPage.RECORDS_OFFSET;
        resultSize = -OWALPage.RECORDS_OFFSET;
      }

      //calculate spare page
      //add all the full pages
      resultSize += length / OWALPage.calculateRecordSize(OWALPage.MAX_ENTRY_SIZE) * OWALPage.PAGE_SIZE;

      int leftSize = (int) length % OWALPage.calculateRecordSize(OWALPage.MAX_ENTRY_SIZE);
      if (leftSize > 0) {
        //add the spare bytes at the last page
        resultSize += OWALPage.RECORDS_OFFSET + OWALPage.calculateSerializedSize(leftSize);
      }

      return new OLogRecord(record, from, from + resultSize);
    }
  }

  public OLogSequenceNumber logRecord(byte[] record) throws IOException {
    flushNewData = true;

    OLogRecord rec = generateLogRecord(filledUpTo, record);
    filledUpTo = rec.writeTo;
    last = new OLogSequenceNumber(order, rec.writeFrom);
    try {
      cacheLock.lock();
      logCache.add(rec);
    } finally {
      cacheLock.unlock();
    }

    long pagesInCache = (filledUpTo - writtenUpTo) / OWALPage.PAGE_SIZE;
    if (pagesInCache > maxPagesCacheSize) {
      OLogManager.instance()
          .info(this, "Max cache limit is reached (%d vs. %d), sync flush is performed", maxPagesCacheSize, pagesInCache);

      writeAheadLog.incrementCacheOverflowCount();

      flush();
    }
    return last;
  }

  @SuppressFBWarnings(value = "PZLA_PREFER_ZERO_LENGTH_ARRAYS")
  public byte[] readRecord(OLogSequenceNumber lsn) throws IOException {
    final OPair<OLogSequenceNumber, byte[]> lastRecord = lastReadRecord.get();
    if (lastRecord != null && lastRecord.getKey().equals(lsn))
      return lastRecord.getValue();

    assert lsn.getSegment() == order;
    if (lsn.getPosition() >= filledUpTo)
      return null;

    if (!logCache.isEmpty())
      flush();

    long pageIndex = lsn.getPosition() / OWALPage.PAGE_SIZE;

    byte[] record = null;
    int pageOffset = (int) (lsn.getPosition() % OWALPage.PAGE_SIZE);

    long pageCount = (filledUpTo + OWALPage.PAGE_SIZE - 1) / OWALPage.PAGE_SIZE;

    while (pageIndex < pageCount) {
      byte[] pageContent = new byte[OWALPage.PAGE_SIZE];
      fileLock.lock();
      try {
        final RandomAccessFile rndFile = getRndFile();
        rndFile.seek(pageIndex * OWALPage.PAGE_SIZE);
        rndFile.readFully(pageContent);
      } finally {
        fileLock.unlock();
      }

      if (!checkPageIntegrity(pageContent))
        throw new OWALPageBrokenException("WAL page with index " + pageIndex + " is broken");

      final ByteBuffer buffer = byteBufferPool.acquireDirect(false);
      buffer.put(pageContent);
      try {
        OWALPage page = new OWALPage(buffer, false);

        byte[] content = page.getRecord(pageOffset);
        if (record == null)
          record = content;
        else {
          byte[] oldRecord = record;

          record = new byte[record.length + content.length];
          System.arraycopy(oldRecord, 0, record, 0, oldRecord.length);
          System.arraycopy(content, 0, record, oldRecord.length, record.length - oldRecord.length);
        }

        if (page.mergeWithNextPage(pageOffset)) {
          pageOffset = OWALPage.RECORDS_OFFSET;
          pageIndex++;
          if (pageIndex >= pageCount)
            throw new OWALPageBrokenException("WAL page with index " + pageIndex + " is broken");
        } else {
          if (page.getFreeSpace() >= OWALPage.MIN_RECORD_SIZE && pageIndex < pageCount - 1)
            throw new OWALPageBrokenException("WAL page with index " + pageIndex + " is broken");

          break;
        }
      } finally {
        byteBufferPool.release(buffer);
      }
    }

    lastReadRecord = new WeakReference<OPair<OLogSequenceNumber, byte[]>>(new OPair<OLogSequenceNumber, byte[]>(lsn, record));
    return record;
  }

  public OLogSequenceNumber getNextLSN(OLogSequenceNumber lsn) throws IOException {
    final byte[] record = readRecord(lsn);
    if (record == null)
      return null;

    long pos = lsn.getPosition();
    long pageIndex = pos / OWALPage.PAGE_SIZE;
    int pageOffset = (int) (pos - pageIndex * OWALPage.PAGE_SIZE);

    int restOfRecord = record.length;
    while (restOfRecord > 0) {
      int entrySize = OWALPage.calculateSerializedSize(restOfRecord);
      if (entrySize + pageOffset < OWALPage.PAGE_SIZE) {
        if (entrySize + pageOffset <= OWALPage.PAGE_SIZE - OWALPage.MIN_RECORD_SIZE)
          pos += entrySize;
        else
          pos += OWALPage.PAGE_SIZE - pageOffset + OWALPage.RECORDS_OFFSET;
        break;
      } else if (entrySize + pageOffset == OWALPage.PAGE_SIZE) {
        pos += entrySize + OWALPage.RECORDS_OFFSET;
        break;
      } else {
        long chunkSize = OWALPage.calculateRecordSize(OWALPage.PAGE_SIZE - pageOffset);
        restOfRecord -= chunkSize;

        pos += OWALPage.PAGE_SIZE - pageOffset + OWALPage.RECORDS_OFFSET;
        pageOffset = OWALPage.RECORDS_OFFSET;
      }
    }

    if (pos >= filledUpTo)
      return null;

    return new OLogSequenceNumber(order, pos);
  }

  public void close(boolean flush) throws IOException {
    if (!closed) {
      lastReadRecord.clear();

      stopFlush(flush);

      if (!closer.isShutdown()) {
        closer.shutdown();
        try {
          if (!closer.awaitTermination(OGlobalConfiguration.WAL_SHUTDOWN_TIMEOUT.getValueAsInteger(), TimeUnit.MILLISECONDS))
            throw new OStorageException("WAL file auto close task '" + getPath() + "' cannot be stopped");

        } catch (InterruptedException e) {
          OLogManager.instance().error(this, "Shutdown of file auto close thread was interrupted");
        }
      }

      fileLock.lock();
      try {
        if (rndFile != null) {
          rndFile.close();
          rndFile = null;
        }
      } finally {
        fileLock.unlock();
      }

      closed = true;
    }
  }

  public OLogSequenceNumber readFlushedLSN() throws IOException {
    fileLock.lock();
    try {
      final RandomAccessFile rndFile = getRndFile();

      long pages = rndFile.length() / OWALPage.PAGE_SIZE;
      if (pages == 0)
        return null;
    } finally {
      fileLock.unlock();
    }

    return new OLogSequenceNumber(order, filledUpTo - 1);
  }

  public void flush() {
    if (!commitExecutor.isShutdown()) {
      try {
        commitExecutor.submit(new FlushTask(true)).get();
      } catch (InterruptedException e) {
        Thread.interrupted();
        throw OException.wrapException(new OStorageException("Thread was interrupted during flush"), e);
      } catch (ExecutionException e) {
        throw OException.wrapException(new OStorageException("Error during WAL segment '" + getPath() + "' flush"), e);
      }
    } else {
      new FlushTask(true).run();
    }
  }

  private void initPageCache() throws IOException {
    fileLock.lock();
    try {
      final RandomAccessFile rndFile = getRndFile();
      long pagesCount = rndFile.length() / OWALPage.PAGE_SIZE;
      if (pagesCount == 0)
        return;

      rndFile.seek((pagesCount - 1) * OWALPage.PAGE_SIZE);
      byte[] content = new byte[OWALPage.PAGE_SIZE];
      rndFile.readFully(content);

      if (checkPageIntegrity(content)) {
        int freeSpace = OIntegerSerializer.INSTANCE.deserializeNative(content, OWALPage.FREE_SPACE_OFFSET);
        filledUpTo = (pagesCount - 1) * OWALPage.PAGE_SIZE + (OWALPage.PAGE_SIZE - freeSpace);
      } else {
        filledUpTo = pagesCount * OWALPage.PAGE_SIZE + OWALPage.RECORDS_OFFSET;
      }
    } finally {
      fileLock.unlock();
    }
  }

  private long extractOrder(String name) {
    final Matcher matcher = Pattern.compile("^.*\\.(\\d+)\\.wal$").matcher(name);

    final boolean matches = matcher.find();
    assert matches;

    final String order = matcher.group(1);
    try {
      return Long.parseLong(order);
    } catch (NumberFormatException e) {
      // never happen
      throw new IllegalStateException(e);
    }
  }

  private boolean checkPageIntegrity(byte[] content) {
    final long magicNumber = OLongSerializer.INSTANCE.deserializeNative(content, OWALPage.MAGIC_NUMBER_OFFSET);
    if (magicNumber != OWALPage.MAGIC_NUMBER)
      return false;

    final CRC32 crc32 = new CRC32();
    crc32.update(content, OIntegerSerializer.INT_SIZE, OWALPage.PAGE_SIZE - OIntegerSerializer.INT_SIZE);

    return ((int) crc32.getValue()) == OIntegerSerializer.INSTANCE.deserializeNative(content, 0);
  }

  private void selfCheck() throws IOException {
    if (!logCache.isEmpty())
      throw new IllegalStateException("WAL cache is not empty, we cannot verify WAL after it was started to be used");

    fileLock.lock();
    try {
      final RandomAccessFile rndFile = getRndFile();
      long pagesCount = rndFile.length() / OWALPage.PAGE_SIZE;

      if (rndFile.length() % OWALPage.PAGE_SIZE > 0) {
        OLogManager.instance().error(this, "Last WAL page was written partially, auto fix");

        rndFile.setLength(OWALPage.PAGE_SIZE * pagesCount);
      }
    } finally {
      fileLock.unlock();
    }
  }

  public long getFilledUpTo() {
    return filledUpTo;
  }

  /**
   * Timer task which is used to close file if it is not accessed during {@link #fileTTL} interval.
   */
  class FileCloser implements Runnable {
    private          boolean            stopped = false;
    private volatile ScheduledFuture<?> self    = null;

    @Override
    public void run() {
      if (stopped) {
        //this task is finished we should stop its execution
        if (self != null) {
          self.cancel(false);
        }

        return;
      }

      if (preventAutoClose) {
        return;
      }

      fileLock.lock();
      try {
        if (closeNextTime) {
          try {
            if (rndFile != null) {
              rndFile.close();
              rndFile = null;
            }
          } catch (IOException e) {
            OLogManager.instance().error(this, "Can not auto close file in WAL", e);
          }

          autoCloseInProgress.set(false);
          stopped = true;

          if (self != null)
            self.cancel(false);

        } else {
          //reschedule himself
          closeNextTime = true;
        }
      } finally {
        fileLock.unlock();
      }
    }
  }

}
