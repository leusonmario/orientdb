/* Generated By:JJTree: Do not edit this line. OArrayRangeSelector.java Version 4.3 */
/* JavaCCOptions:MULTI=true,NODE_USES_PARSER=false,VISITOR=true,TRACK_TOKENS=true,NODE_PREFIX=O,NODE_EXTENDS=,NODE_FACTORY=,SUPPORT_CLASS_VISIBILITY_PUBLIC=true */
package com.orientechnologies.orient.core.sql.parser;

import com.orientechnologies.common.collection.OMultiValue;
import com.orientechnologies.orient.core.command.OCommandContext;
import com.orientechnologies.orient.core.db.record.OIdentifiable;

import java.util.Arrays;
import java.util.Map;

public class OArrayRangeSelector extends SimpleNode {
  protected Integer              from;
  protected Integer              to;
  boolean                        newRange = false;

  protected OArrayNumberSelector fromSelector;
  protected OArrayNumberSelector toSelector;

  public OArrayRangeSelector(int id) {
    super(id);
  }

  public OArrayRangeSelector(OrientSql p, int id) {
    super(p, id);
  }

  /** Accept the visitor. **/
  public Object jjtAccept(OrientSqlVisitor visitor, Object data) {
    return visitor.visit(this, data);
  }

  public void toString(Map<Object, Object> params, StringBuilder builder) {
    if (from != null) {
      builder.append(from);
    } else {
      fromSelector.toString(params, builder);
    }
    if (newRange) {
      builder.append("-");
      // TODO in 3.0 result.append("..");
    } else {
      builder.append("-");
    }
    if (to != null) {
      builder.append(to);
    } else {
      toSelector.toString(params, builder);
    }
  }

  public Object execute(OIdentifiable iCurrentRecord, Object result, OCommandContext ctx) {
    if (result == null) {
      return null;
    }
    if (!OMultiValue.isMultiValue(result)) {
      return null;
    }
    Integer lFrom = from;
    if (fromSelector != null) {
      lFrom = fromSelector.getValue(iCurrentRecord, result, ctx);
    }
    if (lFrom == null) {
      lFrom = 0;
    }
    Integer lTo = to;
    if (toSelector != null) {
      lTo = toSelector.getValue(iCurrentRecord, result, ctx);
    }
    if (lFrom > lTo) {
      return null;
    }
    Object[] arrayResult = OMultiValue.array(result);

    if (arrayResult == null || arrayResult.length == 0) {
      return arrayResult;
    }
    lFrom = Math.max(lFrom, 0);
    if (arrayResult.length < lFrom) {
      return null;
    }
    lFrom = Math.min(lFrom, arrayResult.length - 1);

    lTo = Math.min(lTo, arrayResult.length);

    return Arrays.asList(Arrays.copyOfRange(arrayResult, lFrom, lTo));
  }

}
/* JavaCC - OriginalChecksum=594a372e31fcbcd3ed962c2260e76468 (do not edit this line) */