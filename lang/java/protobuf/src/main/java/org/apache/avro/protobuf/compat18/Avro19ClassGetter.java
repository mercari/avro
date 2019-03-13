package org.apache.avro.protobuf.compat18;

import org.apache.avro.AvroRuntimeException;
import org.apache.avro.Schema;
import org.apache.avro.util.ClassUtils;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.avro.generic.GenericData.STRING_PROP;

/**
 * Class getter from SpecificData in avro-1.9
 * It enables avro-protobuf-1.9 not to depend on avro-1.9 to simplify dependency issue.
 *
 */
public class Avro19ClassGetter {

  private static final Avro19ClassGetter INSTANCE = new Avro19ClassGetter();
  private static final Class NO_CLASS = new Object() {}.getClass();
  private static final Schema NULL_SCHEMA = Schema.create(Schema.Type.NULL);
  private static final String STRING_TYPE_STRING = "String";

  public static Avro19ClassGetter get() {
    return INSTANCE;
  }

  private Map<String, Class> classCache = new ConcurrentHashMap<>();

  public ClassLoader getClassLoader() { return getClass().getClassLoader(); }

  /**
   * Return the class that implements a schema, or null if none exists.
   */
  public Class getClass(Schema schema) {
    switch (schema.getType()) {
      case FIXED:
      case RECORD:
      case ENUM:
        String name = schema.getFullName();
        if (name == null) return null;
        Class c = classCache.get(name);
        if (c == null) {
          try {
            c = ClassUtils.forName(getClassLoader(), getClassName(schema));
          } catch (ClassNotFoundException e) {
            try {                                   // nested class?
              c = ClassUtils.forName(getClassLoader(), getNestedClassName(schema));
            } catch (ClassNotFoundException ex) {
              c = NO_CLASS;
            }
          }
          classCache.put(name, c);
        }
        return c == NO_CLASS ? null : c;
      case ARRAY:
        return List.class;
      case MAP:
        return Map.class;
      case UNION:
        List<Schema> types = schema.getTypes();     // elide unions with null
        if ((types.size() == 2) && types.contains(NULL_SCHEMA))
          return getWrapper(types.get(types.get(0).equals(NULL_SCHEMA) ? 1 : 0));
        return Object.class;
      case STRING:
        if (STRING_TYPE_STRING.equals(schema.getProp(STRING_PROP)))
          return String.class;
        return CharSequence.class;
      case BYTES:
        return ByteBuffer.class;
      case INT:
        return Integer.TYPE;
      case LONG:
        return Long.TYPE;
      case FLOAT:
        return Float.TYPE;
      case DOUBLE:
        return Double.TYPE;
      case BOOLEAN:
        return Boolean.TYPE;
      case NULL:
        return Void.TYPE;
      default:
        throw new AvroRuntimeException("Unknown type: " + schema);
    }
  }

  private Class getWrapper(Schema schema) {
    switch (schema.getType()) {
      case INT:
        return Integer.class;
      case LONG:
        return Long.class;
      case FLOAT:
        return Float.class;
      case DOUBLE:
        return Double.class;
      case BOOLEAN:
        return Boolean.class;
    }
    return getClass(schema);
  }

  /**
   * Returns the Java class name indicated by a schema's name and namespace.
   */
  private String getClassName(Schema schema) {
    String namespace = schema.getNamespace();
    String name = schema.getName();
    if (namespace == null || "".equals(namespace))
      return name;
    String dot = namespace.endsWith("$") ? "" : "."; // back-compatibly handle $
    return namespace + dot + name;
  }

  private String getNestedClassName(Schema schema) {
    String namespace = schema.getNamespace();
    String name = schema.getName();
    if (namespace == null || "".equals(namespace))
      return name;
    return namespace + "$" + name;
  }
}
