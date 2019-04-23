/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.avro;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.avro.generic.GenericData;
import org.apache.avro.Schema.Field;
import org.apache.avro.Schema.SeenPair;
import org.apache.avro.Resolver.ErrorAction.ErrorType;

/**
 * Encapsulate schema-resolution logic in an easy-to-consume representation. See
 * {@link #resolve} and also the separate document entitled
 * <tt>refactoring-resolution</tt> for more information. It might also be
 * helpful to study {@link org.apache.avro.io.parsing.ResolvingGrammarGenerator}
 * as an example of how to use this class.
 */
public class Resolver {
  /**
   * Returns a {@link Resolver.Action} tree for resolving the writer schema
   * <tt>writer</tt> and the reader schema <tt>reader</tt>.
   *
   * This method walks the reader's and writer's schemas together, generating an
   * appropriate subclass of {@link Action} to encapsulate the information needed
   * to resolve the corresponding parts of each schema tree. For convience, every
   * {@link Action} object has a pointer to the corresponding parts of the
   * reader's and writer's trees being resolved by the action. Each subclass of
   * {@link Action} has additional information needed for different types of
   * schema, e.g., the {@link EnumAdjust} subclass has information about
   * re-ordering and deletion of enumeration symbols, while {@link RecordAdjust}
   * has information about re-ordering and deletion of record fields.
   *
   * Note that aliases are applied to the writer's schema before resolution
   * actually takes place. This means that the <tt>writer</tt> field of the
   * resulting {@link Action} objects will not be the same schema as provided to
   * this method. However, the <tt>reader</tt> field will be.
   *
   * @param writer The schema used by the writer
   * @param reader The schema used by the reader
   * @param data   Used for <tt>getDefaultValue</tt> and getting conversions
   * @return Nested actions for resolving the two
   */
  public static Action resolve(Schema writer, Schema reader, GenericData data) {
    return resolve(Schema.applyAliases(writer, reader), reader, data, new HashMap<>());
  }

  /**
   * Uses <tt>GenericData.get()</tt> for the <tt>data</tt> param.
   */
  public static Action resolve(Schema writer, Schema reader) {
    return resolve(writer, reader, GenericData.get());
  }

  private static Action resolve(Schema w, Schema r, GenericData d, Map<SeenPair, Action> seen) {
    final Schema.Type wType = w.getType();
    final Schema.Type rType = r.getType();

    if (wType == Schema.Type.UNION)
      return WriterUnion.resolve(w, r, d, seen);

    if (wType == rType) {
      switch (wType) {
      case NULL:
      case BOOLEAN:
      case INT:
      case LONG:
      case FLOAT:
      case DOUBLE:
      case STRING:
      case BYTES:
        return new DoNothing(w, r, d);

      case FIXED:
        if (w.getFullName() != null && !w.getFullName().equals(r.getFullName()))
          return new ErrorAction(w, r, d, ErrorType.NAMES_DONT_MATCH);
        else if (w.getFixedSize() != r.getFixedSize())
          return new ErrorAction(w, r, d, ErrorType.SIZES_DONT_MATCH);
        else
          return new DoNothing(w, r, d);

      case ARRAY:
        Action et = resolve(w.getElementType(), r.getElementType(), d, seen);
        return new Container(w, r, d, et);

      case MAP:
        Action vt = resolve(w.getValueType(), r.getValueType(), d, seen);
        return new Container(w, r, d, vt);

      case ENUM:
        return EnumAdjust.resolve(w, r, d);

      case RECORD:
        return RecordAdjust.resolve(w, r, d, seen);

      default:
        throw new IllegalArgumentException("Unknown type for schema: " + wType);
      }
    } else if (rType == Schema.Type.UNION)
      return ReaderUnion.resolve(w, r, d, seen);
    else
      return Promote.resolve(w, r, d);
  }

  /**
   * An abstract class for an action to be taken to resolve a writer's schema
   * (found in public instance variable <tt>writer</tt>) against a reader's schema
   * (in <tt>reader</tt>). Ordinarily, neither field can be <tt>null</tt>, except
   * that the <tt>reader</tt> field can be <tt>null</tt> in a {@link Skip}, which
   * is used to skip a field in a writer's record that doesn't exist in the
   * reader's (and thus there is no reader schema to resolve to).
   */
  public static abstract class Action {
    /** Helps us traverse faster. */
    public static enum Type {
      DO_NOTHING, ERROR, PROMOTE, CONTAINER, ENUM, SKIP, RECORD, WRITER_UNION, READER_UNION
    };

    public final Schema writer, reader;
    public final Type type;

    /**
     * If the reader has a logical type, it's stored here for fast access, otherwise
     * this will be null.
     */
    public final LogicalType logicalType;

    /**
     * If the reader has a conversion that needs to be applied, it's stored here for
     * fast access, otherwise this will be null.
     */
    public final Conversion<?> conversion;

    protected Action(Schema w, Schema r, GenericData data, Type t) {
      this.writer = w;
      this.reader = r;
      this.type = t;
      if (r == null) {
        this.logicalType = null;
        this.conversion = null;
      } else {
        this.logicalType = r.getLogicalType();
        this.conversion = data.getConversionFor(logicalType);
      }
    }
  }

  /**
   * In this case, there's nothing to be done for resolution: the two schemas are
   * effectively the same. This action will be generated <em>only</em> for
   * primitive types and fixed types, and not for any other kind of schema.
   */
  public static class DoNothing extends Action {
    public DoNothing(Schema w, Schema r, GenericData d) {
      super(w, r, d, Action.Type.DO_NOTHING);
    }
  }

  /**
   * In this case there is an error. We put error Actions into trees because Avro
   * reports these errors in a lazy fashion: if a particular input doesn't
   * "tickle" the error (typically because it's in a branch of a union that isn't
   * found in the data being read), then it's safe to ignore it.
   */
  public static class ErrorAction extends Action {
    public static enum ErrorType {
      /**
       * Use when Schema types don't match and can't be converted. For example,
       * resolving "int" and "enum".
       */
      INCOMPATIBLE_SCHEMA_TYPES,

      /**
       * Use when Schema types match but, in the case of record, enum, or fixed, the
       * names don't match.
       */
      NAMES_DONT_MATCH,

      /**
       * Use when two fixed types match and their names match by their sizes don't.
       */
      SIZES_DONT_MATCH,

      /**
       * Use when matching two records and the reader has a field with no default
       * value and that field is missing in the writer..
       */
      MISSING_REQUIRED_FIELD,

      /**
       * Use when matching a reader's union against a non-union and can't find a
       * branch that matches.
       */
      NO_MATCHING_BRANCH
    }

    public final ErrorType error;

    public ErrorAction(Schema w, Schema r, GenericData d, ErrorType e) {
      super(w, r, d, Action.Type.ERROR);
      this.error = e;
    }

    public String toString() {
      String result;
      switch (this.error) {
      case INCOMPATIBLE_SCHEMA_TYPES:
      case NAMES_DONT_MATCH:
      case SIZES_DONT_MATCH:
      case NO_MATCHING_BRANCH:
        return "Found " + writer.getFullName() + ", expecting " + reader.getFullName();

      case MISSING_REQUIRED_FIELD: {
        List<Field> wfields = writer.getFields();
        List<Field> rfields = reader.getFields();
        String fname = "<oops>";
        for (Field rf : rfields)
          if (writer.getField(rf.name()) == null && rf.defaultValue() == null)
            fname = rf.name();
        return ("Found " + writer.getFullName() + ", expecting " + reader.getFullName() + ", missing required field "
            + fname);
      }
      default:
        throw new IllegalArgumentException("Unknown error.");
      }
    }
  }

  /**
   * In this case, the writer's type needs to be promoted to the reader's. These
   * are constructed by {@link Promote.resolve}, which will only construct one
   * when the writer's and reader's schemas are different (ie, no "self
   * promotion"), and whent the promotion is one allowed by the Avro spec.
   */
  public static class Promote extends Action {
    private Promote(Schema w, Schema r, GenericData d) {
      super(w, r, d, Action.Type.PROMOTE);
    }

    /**
     * Return a promotion.
     * 
     * @param w Writer's schema
     * @param r Rearder's schema
     * @result a {@link Promote} schema if the two schemas are compatible, or
     *         {@link ErrorType.INCOMPATIBLE_SCHEMA_TYPE} if they are not.
     * @throws IllegalArgumentException if <em>getType()</em> of the two schemas are
     *                                  not different.
     */
    public static Action resolve(Schema w, Schema r, GenericData d) {
      if (isValid(w, r))
        return new Promote(w, r, d);
      else
        return new ErrorAction(w, r, d, ErrorType.INCOMPATIBLE_SCHEMA_TYPES);
    }

    /**
     * Returns true iff <tt>w</tt> and <tt>r</tt> are both primitive types and
     * either they are the same type or <tt>w</tt> is promotable to <tt>r</tt>.
     * Should
     */
    public static boolean isValid(Schema w, Schema r) {
      if (w.getType() == r.getType())
        throw new IllegalArgumentException("Only use when reader and writer are different.");
      Schema.Type wt = w.getType();
      switch (r.getType()) {
      case INT:
        switch (wt) {
        case INT:
          return true;
        }
        break;
      case LONG:
        switch (wt) {
        case INT:
        case LONG:
          return true;
        }
        break;
      case FLOAT:
        switch (wt) {
        case INT:
        case LONG:
        case FLOAT:
          return true;
        }
        break;
      case DOUBLE:
        switch (wt) {
        case INT:
        case LONG:
        case FLOAT:
        case DOUBLE:
          return true;
        }
        break;
      case BYTES:
      case STRING:
        switch (wt) {
        case STRING:
        case BYTES:
          return true;
        }
        break;
      }
      return false;
    }
  }

  /**
   * Used for array and map schemas: the public instance variable
   * <tt>elementAction</tt> contains the resolving action needed for the element
   * type of an array or value top of a map.
   */
  public static class Container extends Action {
    public final Action elementAction;

    public Container(Schema w, Schema r, GenericData d, Action e) {
      super(w, r, d, Action.Type.CONTAINER);
      this.elementAction = e;
    }
  }

  /**
   * Contains information needed to resolve enumerations. When resolving enums,
   * adjustments need to be made in two scenarios: the index for an enum symbol
   * might be different in the reader or writer, or the reader might not have a
   * symbol that was written out for the writer (which is an error, but one we can
   * only detect when decoding data).
   *
   * These adjustments are reflected in the instance variable
   * <tt>adjustments</tt>. For the symbol with index <tt>i</tt> in the writer's
   * enum definition, <tt>adjustments[i]</tt> -- and integer -- contains the
   * adjustment for that symbol. If the integer is positive, then reader also has
   * the symbol and the integer is its index in the reader's schema. If
   * <tt>adjustment[i]</tt> is negative, then the reader does <em>not</em> have
   * the corresponding symbol (which is the error case).
   *
   * Sometimes there's no adjustments needed: all symbols in the reader have the
   * same index in the reader's and writer's schema. This is a common case, and it
   * allows for some optimization. To signal that this is the case,
   * <tt>noAdjustmentsNeeded</tt> is set to true.
   */
  public static class EnumAdjust extends Action {
    public final int[] adjustments;
    public final boolean noAdjustmentsNeeded;

    private EnumAdjust(Schema w, Schema r, GenericData d, int[] adj) {
      super(w, r, d, Action.Type.ENUM);
      this.adjustments = adj;
      boolean noAdj = true;
      int rsymCount = r.getEnumSymbols().size();
      int count = Math.min(rsymCount, adj.length);
      noAdj = (adj.length <= rsymCount);
      for (int i = 0; noAdj && i < count; i++)
        noAdj &= (i == adj[i]);
      this.noAdjustmentsNeeded = noAdj;
    }

    /**
     * If writer and reader don't have same name, a
     * {@link ErrorAction.Type.NAMES_DONT_MATCH} is returned, otherwise an
     * appropriate {@link EnumAdjust} is.
     */
    public static Action resolve(Schema w, Schema r, GenericData d) {
      if (w.getFullName() != null && !w.getFullName().equals(r.getFullName()))
        return new ErrorAction(w, r, d, ErrorType.NAMES_DONT_MATCH);

      final List<String> wsymbols = w.getEnumSymbols();
      final List<String> rsymbols = r.getEnumSymbols();
      final int defaultIndex = (r.getEnumDefault() == null ? -1 : rsymbols.indexOf(r.getEnumDefault()));
      int[] adjustments = new int[wsymbols.size()];
      for (int i = 0; i < adjustments.length; i++) {
        int j = rsymbols.indexOf(wsymbols.get(i));
        adjustments[i] = (0 <= j ? j : defaultIndex);
      }
      return new EnumAdjust(w, r, d, adjustments);
    }
  }

  /**
   * This only appears inside {@link RecordAdjust.fieldActions}, i.e., the actions
   * for adjusting the fields of a record. This action indicates that the writer's
   * schema has a field that the reader's does <em>not</em> have, and thus the
   * field should be skipped. Since there is no corresponding reader's schema for
   * the writer's in this case, the {@link Action.reader} field is <tt>null</tt>
   * for this subclass.
   */
  public static class Skip extends Action {
    public Skip(Schema w, GenericData d) {
      super(w, null, d, Action.Type.SKIP);
    }
  }

  /**
   * Instructions for resolving two record schemas. Includes instructions on how
   * to recursively resolve each field, an indication of when to skip (writer
   * fields), plus information about which reader fields should be populated by
   * defaults (because the writer doesn't have corresponding fields).
   */
  public static class RecordAdjust extends Action {
    /**
     * An action for each field of the writer. If the corresponding field is to be
     * skipped during reading, then this will contain a {@link Skip}. For fields to
     * be read into the reading datum, will contain a regular action for resolving
     * the writer/reader schemas of the matching fields.
     */
    public final Action[] fieldActions;

    /**
     * Contains (all of) the reader's fields. The first <i>n</i> of these are the
     * fields that will be read from the writer: these <i>n</i> are in the order
     * dictated by writer's schema. The remaining <i>m</i> fields will be read from
     * default values (actions for these default values are found in
     * {@link defaults}.
     */
    public final Field[] readerOrder;

    /**
     * Pointer into {@link readerOrder} of the first reader field whose value comes
     * from a default value. Set to length of {@link readerOrder} if there are none.
     */
    public final int firstDefault;

    /**
     * Contains the default values to be used for the last
     * <tt>readerOrder.length-firstDefault</tt> fields in rearderOrder. The
     * <tt>i</tt>th element of <tt>defaults</tt> is the default value for the
     * <tt>i+firstDefault</tt> member of <tt>readerOrder</tt>.
     */
    public final Object[] defaults;

    /**
     * Returns true iff <code>i&nbsp;==&nbsp;readerOrder[i].pos()</code> for all
     * indices <code>i</code>. Which is to say: the order of the reader's fields is
     * the same in both the reader's and writer's schema.
     */
    public boolean noReorder() {
      boolean result = true;
      for (int i = 0; result && i < readerOrder.length; i++)
        result &= (i == readerOrder[i].pos());
      return result;
    }

    private RecordAdjust(Schema w, Schema r, GenericData d, Action[] fa, Field[] ro, int firstD, Object[] defaults) {
      super(w, r, d, Action.Type.RECORD);
      this.fieldActions = fa;
      this.readerOrder = ro;
      this.firstDefault = firstD;
      this.defaults = defaults;
    }

    /**
     * Returns a {@link RecordAdjust} for the two schemas, or an {@link ErrorAction}
     * if there was a problem resolving. An {@link ErrorAction} is returned when
     * either the two record-schemas don't have the same name, or if the writer is
     * missing a field for which the reader does not have a default value.
     * 
     * @throws RuntimeException if writer and reader schemas are not both records
     */
    static Action resolve(Schema w, Schema r, GenericData d, Map<SeenPair, Action> seen) {
      SeenPair wr = new SeenPair(w, r);
      Action result = seen.get(wr);
      if (result != null)
        return result;

      /*
       * Current implementation doesn't do this check. To pass regressions tests, we
       * can't either. if (w.getFullName() != null && !
       * w.getFullName().equals(r.getFullName())) { result = new ErrorAction(w, r, d,
       * ErrorType.NAMES_DONT_MATCH); seen.put(wr, result); return result; }
       */
      List<Field> wfields = w.getFields();
      List<Field> rfields = r.getFields();

      int firstDefault = 0;
      for (Schema.Field wf : wfields)
        if (r.getField(wf.name()) != null)
          firstDefault++;
      Action[] actions = new Action[wfields.size()];
      Field[] reordered = new Field[rfields.size()];
      Object[] defaults = new Object[reordered.length - firstDefault];
      result = new RecordAdjust(w, r, d, actions, reordered, firstDefault, defaults);
      seen.put(wr, result); // Insert early to handle recursion

      int i = 0;
      int ridx = 0;
      for (Field wField : wfields) {
        Field rField = r.getField(wField.name());
        if (rField != null) {
          reordered[ridx++] = rField;
          actions[i++] = Resolver.resolve(wField.schema(), rField.schema(), d, seen);
        } else
          actions[i++] = new Skip(wField.schema(), d);
      }
      for (Field rf : rfields)
        if (w.getField(rf.name()) == null)
          if (rf.defaultValue() == null) {
            result = new ErrorAction(w, r, d, ErrorType.MISSING_REQUIRED_FIELD);
            seen.put(wr, result);
            return result;
          } else {
            defaults[ridx - firstDefault] = d.getDefaultValue(rf);
            reordered[ridx++] = rf;
          }
      return result;
    }
  }

  /**
   * In this case, the writer was a union. There are two subcases here:
   *
   * If the reader and writer are the same union, then the <tt>unionEquiv</tt>
   * variable is set to true and the <tt>actions</tt> list holds the resolutions
   * of each branch of the writer against the corresponding branch of the reader
   * (which will result in no material resolution work, because the branches will
   * be equivalent). If they reader is not a union or is a different union, then
   * <tt>unionEquiv</tt> is false and the <tt>actions</tt> list holds the
   * resolution of each of the writer's branches against the entire schema of the
   * reader (if the reader is a union, that will result in ReaderUnion actions).
   */
  public static class WriterUnion extends Action {
    public final Action[] actions;
    public final boolean unionEquiv;

    private WriterUnion(Schema w, Schema r, GenericData d, boolean ue, Action[] a) {
      super(w, r, d, Action.Type.WRITER_UNION);
      unionEquiv = ue;
      actions = a;
    }

    public static Action resolve(Schema w, Schema r, GenericData d, Map<SeenPair, Action> seen) {
      boolean ueqv = unionEquiv(w, r, new HashMap<>());
      List<Schema> wb = w.getTypes();
      List<Schema> rb = (ueqv ? r.getTypes() : null);
      int sz = wb.size();
      Action[] actions = new Action[sz];
      for (int i = 0; i < sz; i++)
        actions[i] = Resolver.resolve(wb.get(i), (ueqv ? rb.get(i) : r), d, seen);
      return new WriterUnion(w, r, d, ueqv, actions);
    }
  }

  /**
   * In this case, the reader is a union and the writer is not. For this case, we
   * need to pick the first branch of the reader that matches the writer and
   * pretend to the reader that the index of this branch was found in the writer's
   * data stream.
   *
   * To support this case, the {@link ReaderUnion} object has two (public) fields:
   * <tt>firstMatch</tt> gives the index of the first matching branch in the
   * reader's schema, and <tt>actualResolution</tt> is the {@link Action} that
   * resolves the writer's schema with the schema found in the <tt>firstMatch</tt>
   * branch of the reader's schema.
   */
  public static class ReaderUnion extends Action {
    public final int firstMatch;
    public final Action actualAction;

    public ReaderUnion(Schema w, Schema r, GenericData d, int firstMatch, Action actual) {
      super(w, r, d, Action.Type.READER_UNION);
      this.firstMatch = firstMatch;
      this.actualAction = actual;
    }

    /**
     * Returns a {@link ReaderUnion} action for resolving <tt>w</tt> and <tt>r</tt>,
     * or an {@link ErrorAction} if there is no branch in the reader that matches
     * the writer.
     * 
     * @throws RuntimeException if <tt>r</tt> is not a union schema or <tt>w</tt>
     *                          <em>is</em> a union schema
     */
    public static Action resolve(Schema w, Schema r, GenericData d, Map<SeenPair, Action> seen) {
      if (w.getType() == Schema.Type.UNION)
        throw new IllegalArgumentException("Writer schema is union.");
      int i = firstMatchingBranch(w, r, d, seen);
      if (0 <= i)
        return new ReaderUnion(w, r, d, i, Resolver.resolve(w, r.getTypes().get(i), d, seen));
      return new ErrorAction(w, r, d, ErrorType.NO_MATCHING_BRANCH);
    }

    // Note: This code was taken verbatim from the 1.8.x branch of Avro. It
    // implements
    // a "soft match" algorithm that seems to disagree with the spec. However, in
    // the
    // interest of "bug-for-bug" compatibility, we imported the old algorithm.
    private static int firstMatchingBranch(Schema w, Schema r, GenericData d, Map<SeenPair, Action> seen) {
      Schema.Type vt = w.getType();
      // first scan for exact match
      int j = 0;
      int structureMatch = -1;
      for (Schema b : r.getTypes()) {
        if (vt == b.getType())
          if (vt == Schema.Type.RECORD || vt == Schema.Type.ENUM || vt == Schema.Type.FIXED) {
            String vname = w.getFullName();
            String bname = b.getFullName();
            // return immediately if the name matches exactly according to spec
            if (vname != null && vname.equals(bname))
              return j;

            if (vt == Schema.Type.RECORD && !hasMatchError(RecordAdjust.resolve(w, b, d, seen))) {
              String vShortName = w.getName();
              String bShortName = b.getName();
              // use the first structure match or one where the name matches
              if ((structureMatch < 0) || (vShortName != null && vShortName.equals(bShortName))) {
                structureMatch = j;
              }
            }
          } else
            return j;
        j++;
      }

      // if there is a record structure match, return it
      if (structureMatch >= 0)
        return structureMatch;

      // then scan match via numeric promotion
      j = 0;
      for (Schema b : r.getTypes()) {
        switch (vt) {
        case INT:
          switch (b.getType()) {
          case LONG:
          case DOUBLE:
          case FLOAT:
            return j;
          }
          break;
        case LONG:
          switch (b.getType()) {
          case DOUBLE:
          case FLOAT:
            return j;
          }
          break;
        case FLOAT:
          switch (b.getType()) {
          case DOUBLE:
            return j;
          }
          break;
        case STRING:
          switch (b.getType()) {
          case BYTES:
            return j;
          }
          break;
        case BYTES:
          switch (b.getType()) {
          case STRING:
            return j;
          }
          break;
        }
        j++;
      }
      return -1;
    }

    private static boolean hasMatchError(Action action) {
      if (action instanceof ErrorAction)
        return true;
      else
        for (Action a : ((RecordAdjust) action).fieldActions)
          if (a instanceof ErrorAction)
            return true;
      return false;
    }
  }

  private static boolean unionEquiv(Schema w, Schema r, Map<SeenPair, Boolean> seen) {
    Schema.Type wt = w.getType();
    if (wt != r.getType())
      return false;
    if ((wt == Schema.Type.RECORD || wt == Schema.Type.FIXED || wt == Schema.Type.ENUM)
        && !(w.getFullName() == null || w.getFullName().equals(r.getFullName())))
      return false;

    switch (w.getType()) {
    case NULL:
    case BOOLEAN:
    case INT:
    case LONG:
    case FLOAT:
    case DOUBLE:
    case STRING:
    case BYTES:
      return true;

    case ARRAY:
      return unionEquiv(w.getElementType(), r.getElementType(), seen);
    case MAP:
      return unionEquiv(w.getValueType(), r.getValueType(), seen);

    case FIXED:
      return w.getFixedSize() == r.getFixedSize();

    case ENUM: {
      List<String> ws = w.getEnumSymbols();
      List<String> rs = r.getEnumSymbols();
      if (ws.size() != rs.size())
        return false;
      int i = 0;
      for (i = 0; i < ws.size(); i++)
        if (!ws.get(i).equals(rs.get(i)))
          break;
      return i == ws.size();
    }

    case UNION: {
      List<Schema> wb = w.getTypes();
      List<Schema> rb = r.getTypes();
      if (wb.size() != rb.size())
        return false;
      int i = 0;
      for (i = 0; i < wb.size(); i++)
        if (!unionEquiv(wb.get(i), rb.get(i), seen))
          break;
      return i == wb.size();
    }

    case RECORD: {
      SeenPair wsc = new SeenPair(w, r);
      if (!seen.containsKey(wsc)) {
        seen.put(wsc, true); // Be optimistic, but we may change our minds
        List<Field> wb = w.getFields();
        List<Field> rb = r.getFields();
        if (wb.size() != rb.size())
          seen.put(wsc, false);
        else {
          int i = 0;
          for (i = 0; i < wb.size(); i++)
            if (!unionEquiv(wb.get(i).schema(), rb.get(i).schema(), seen))
              break;
          seen.put(wsc, (i == wb.size()));
        }
      }
      return seen.get(wsc);
    }
    default:
      throw new IllegalArgumentException("Unknown schema type: " + w.getType());
    }
  }
}
