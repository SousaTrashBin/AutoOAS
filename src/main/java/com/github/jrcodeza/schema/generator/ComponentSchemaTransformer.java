package com.github.jrcodeza.schema.generator;

import at.aau.serg.annotations.Out;
import at.aau.serg.frameworks.ValidationAnnotationProvider;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.jrcodeza.schema.generator.interceptors.SchemaFieldInterceptor;
import com.github.jrcodeza.schema.generator.model.CustomComposedSchema;
import com.github.jrcodeza.schema.generator.model.InheritanceInfo;
import com.github.jrcodeza.schema.generator.util.SchemaGeneratorHelper;
import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.media.Discriminator;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.lang3.StringUtils;
import org.javatuples.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.CtTypeParameterReference;
import spoon.reflect.reference.CtTypeReference;

import java.lang.annotation.Annotation;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ComponentSchemaTransformer {

    private final List<SchemaFieldInterceptor> schemaFieldInterceptors;
    private final SchemaGeneratorHelper schemaGeneratorHelper;

    private static final Logger logger = LoggerFactory.getLogger(ComponentSchemaTransformer.class);

    private final ValidationAnnotationProvider validationAnnotationProvider;

    public ComponentSchemaTransformer(List<SchemaFieldInterceptor> schemaFieldInterceptors,
                                      SchemaGeneratorHelper schemaGeneratorHelper,
                                      ValidationAnnotationProvider validationAnnotationProvider
    ) {
        this.schemaFieldInterceptors = schemaFieldInterceptors;
        this.schemaGeneratorHelper = schemaGeneratorHelper;
        this.validationAnnotationProvider = validationAnnotationProvider;
    }

    public Schema transformSimpleSchema(Class<?> clazz, Map<String, InheritanceInfo> inheritanceMap) {
        throw new NotImplementedException("This method used Reflection and is not used anymore");
    }

    public Schema transformSimpleSchema(CtType<?> clazz, Map<String, InheritanceInfo> inheritanceMap) {
        if (clazz.isEnum()) {
            return schemaGeneratorHelper.createEnumSchema(((CtEnum<?>) clazz).getEnumValues().stream().map(v -> v.getSimpleName()));
        }

        // else is object
        List<String> requiredFields = new ArrayList<>();

        Schema<?> schema = new Schema<>();
        schema.setType("object");
        schemaGeneratorHelper.enrichWithTypeAnnotations(schema,
                schemaGeneratorHelper.getActualAnnotations(clazz.getAnnotations()));

        // Schema Properties
        schema.setProperties(getClassProperties(clazz, requiredFields));

        updateRequiredFields(schema, requiredFields);

        if (inheritanceMap.containsKey(clazz.getQualifiedName())) {
            Discriminator discriminator = createDiscriminator(inheritanceMap.get(clazz.getQualifiedName()));
            schema.setDiscriminator(discriminator);
            enrichWithDiscriminatorProperty(schema, discriminator);
        }

        if (clazz.isInterface() && !clazz.getSuperInterfaces().isEmpty()) {
            // if the first dto is an interface, iterate the extends hierarchy of those
            CtTypeReference<?> firstParent = clazz.getSuperInterfaces().iterator().next();
            // TODO: support multi-inheritance of interfaces
            return traverseAndAddProperties(schema, inheritanceMap, firstParent.getTypeDeclaration(), clazz);

        } else if (clazz.getSuperclass() != null && !(clazz instanceof spoon.reflect.declaration.CtRecord)) {
            return traverseAndAddProperties(schema, inheritanceMap, clazz.getSuperclass().getTypeDeclaration(), clazz);
        }

        return schema;
    }

    public Schema transformExternalSchema(CtTypeReference<?> type) {
        var urlAndDescription = getTypeInformationForExternalSchema(type);

        Schema<?> schema = new Schema<>();
        schema.setType("object");
        schema.setExternalDocs(new ExternalDocumentation()
                .url(urlAndDescription.getValue0())
                .description(urlAndDescription.getValue1())
        );

        return schema;
    }

    private Pair<String, String> getTypeInformationForExternalSchema(CtTypeReference<?> type) {
        var genericExternalPackageString = "external package";

        if (type.getPackage() != null)
            // regular class in package
            return new Pair<>(type.getPackage().getSimpleName(), genericExternalPackageString);

        else if (type.getDeclaringType() != null)
            // inner class
            return new Pair<>(type.getDeclaringType().getPackage().toString(), "Inner class: " + type.getDeclaringType().getSimpleName());

        else {
            var declaration = tryGetGenericTypeDeclaration(type);

            if (declaration != null)
                // generic parameter
                return new Pair<>(tryGetClassDeclaringGenericType(type), "Generic parameter: " + declaration);

            else {
                System.out.println("Encountered unknown, unparsable type: " + type.toStringDebug());
                return new Pair<>("unknown", type.toString());
            }
        }

    }

    /**
     * Returns the declaration string of a generic type parameter, e.g., T extends Serializable,
     * or null if the type is not a generic type.
     *
     * @param type
     * @return
     */
    private String tryGetGenericTypeDeclaration(CtTypeReference<?> type) {
        if (type instanceof CtTypeParameterReference typeParamRef) {
            CtTypeParameter declaration = typeParamRef.getDeclaration();
            if (declaration != null) {
                return declaration.toString();
            }
        }
        return null;
    }

    /**
     * Returns the fully qualified name of the class declaring the generic type parameter, e.g., {@code XX} for {@code X<T>},
     * or null if the type is not a generic type.
     *
     * @param type
     * @return
     */
    private String tryGetClassDeclaringGenericType(CtTypeReference<?> type) {
        return Optional.ofNullable(type)
                .map(CtElement::getParent)
                .map(CtElement::getParent)
                .filter(CtClass.class::isInstance)
                .map(CtClass.class::cast)
                .map(CtClass::getQualifiedName)
                .orElse(null);
    }

    /**
     * Creates a special schema for unspecified types, eg ResponseEntity or ResponseEntity<\?>.
     *
     * @return
     */
    public Schema<?> transformUnspecifiedSchema() {
        return schemaGeneratorHelper.createUnspecifiedSchema();
    }

    private Discriminator createDiscriminator(InheritanceInfo inheritanceInfo) {
        Map<String, String> discriminatorTypeMapping = inheritanceInfo.getDiscriminatorClassMap().entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));

        Discriminator discriminator = new Discriminator();
        discriminator.setPropertyName(inheritanceInfo.getDiscriminatorFieldName());
        discriminator.setMapping(discriminatorTypeMapping);
        return discriminator;
    }

    private void updateRequiredFields(Schema schema, List<String> requiredFields) {
        if (requiredFields == null || requiredFields.isEmpty()) {
            return;
        }
        if (schema.getRequired() == null) {
            schema.setRequired(requiredFields);
            return;
        }
        schema.getRequired().addAll(requiredFields);
    }

    private void updateSchemaProperties(Schema schema, String propertyName, Schema propertyValue) {
        if (StringUtils.isBlank(propertyName) || propertyValue == null) {
            return;
        }
        if (schema.getProperties() == null) {
            schema.setProperties(new HashMap<>());
        }
        schema.getProperties().put(propertyName, propertyValue);
    }

    private void enrichWithDiscriminatorProperty(Schema schema, Discriminator discriminator) {
        if (schema != null && !schema.getProperties().containsKey(discriminator.getPropertyName())) {
            List<String> discriminatorTypeRequiredProperty = new ArrayList<>();
            discriminatorTypeRequiredProperty.add(discriminator.getPropertyName());

            updateSchemaProperties(schema, discriminator.getPropertyName(), new StringSchema());
            updateRequiredFields(schema, discriminatorTypeRequiredProperty);
        }
    }

    private Schema<?> traverseAndAddProperties(Schema<?> schema, Map<String, InheritanceInfo> inheritanceMap,
                                               CtType<?> superclass, CtType<?> actualClass) {
        if (schemaGeneratorHelper.isInPackagesToBeScanned(superclass)) {
            // create a composed schema with allOf
            //  as explained in https://swagger.io/docs/specification/data-models/inheritance-and-polymorphism/
            Schema<?> parentClassSchema = new Schema<>();
            parentClassSchema.set$ref(
                    schemaGeneratorHelper.prepareSchemaReference(superclass.getReference())
            );

            CustomComposedSchema composedSchema = new CustomComposedSchema();
            enrichWithAdditionalProperties(composedSchema, inheritanceMap, superclass.getQualifiedName(), actualClass.getSimpleName());
            composedSchema.setAllOf(Arrays.asList(parentClassSchema, schema));
            composedSchema.setDescription(schema.getDescription());
            return composedSchema;

        } else {
            // adding properties from parent classes is present due to swagger ui bug, after using different ui
            // this becomes relevant only for third party packages
            List<String> requiredFields = new ArrayList<>();
            schema.getProperties().putAll(getClassProperties(superclass, requiredFields));
            updateRequiredFields(schema, requiredFields);
            if (superclass.getSuperclass() != null && !"java.lang".equals(superclass.getSuperclass().getPackage().getSimpleName())) {
                return traverseAndAddProperties(schema, inheritanceMap, superclass.getSuperclass().getTypeDeclaration(), superclass);
            }
            return schema;
        }
    }

    private void enrichWithAdditionalProperties(CustomComposedSchema customComposedSchema, Map<String, InheritanceInfo> inheritanceMap,
                                                String superClassQualifiedName, String actualClassName) {
        if (inheritanceMap.containsKey(superClassQualifiedName)) {
            Map<String, String> discriminatorClassMap = inheritanceMap.get(superClassQualifiedName).getDiscriminatorClassMap();
            if (discriminatorClassMap.containsKey(actualClassName)) {
                customComposedSchema.setDiscriminatorValue(discriminatorClassMap.get(actualClassName));
            }
        }
    }

    /**
     * Returns the properties for the schema component, either based on fields or getter methods including handling of @JsonProperty mappings.
     *
     * @param clazz
     * @param requiredFields
     * @return
     */
    private Map<String, Schema> getClassProperties(CtType<?> clazz, List<String> requiredFields) {
        Map<String, Schema> classPropertyMap = new HashMap<>();
        Set<String> processedFieldNames = new HashSet<>();

        if (clazz instanceof spoon.reflect.declaration.CtRecord record) {
            for (var component : record.getRecordComponents()) {
                var annotations = schemaGeneratorHelper.getActualAnnotations(component.getAnnotations());
                if (schemaGeneratorHelper.hasJsonIgnore(annotations)) continue;
                getFieldOrMethodSchema(component.getSimpleName(), component.getType(),
                        component.getAnnotations(), requiredFields)
                        .ifPresent(schema -> classPropertyMap.put(schema.getName(), schema));
            }
            return classPropertyMap;
        }

        processFields(clazz, requiredFields, classPropertyMap, processedFieldNames);
        processGetterMethods(clazz, requiredFields, classPropertyMap, processedFieldNames);
        processJsonCreatorConstructors(clazz, requiredFields, classPropertyMap, processedFieldNames);

        return classPropertyMap;
    }

    /**
     * Process the actual fields of the clazz and handle @JsonProperty mappings.
     *
     * @param clazz
     * @param requiredFields
     * @param propertyMap
     * @param processedFieldNames
     */
    private void processFields(CtType<?> clazz, List<String> requiredFields, @Out Map<String, Schema> propertyMap, @Out Set<String> processedFieldNames) {
        for (CtField<?> field : clazz.getFields()) {
            // don't consider static fields
            if (field.isStatic())
                continue;

            var fieldAnnotations = schemaGeneratorHelper.getActualAnnotations(field.getAnnotations());
            if (schemaGeneratorHelper.hasJsonIgnore(fieldAnnotations)) {
                processedFieldNames.add(field.getSimpleName());
                continue;
            }

            getFieldSchema(field, requiredFields).ifPresent(schema -> {
                schemaFieldInterceptors.forEach(modelClassFieldInterceptor -> modelClassFieldInterceptor.intercept(clazz, field, schema));
                propertyMap.put(schema.getName(), schema);
                processedFieldNames.add(field.getSimpleName());
            });
        }
    }

    /**
     * Process getter methods and check for @JsonProperty mappings.
     *
     * @param clazz
     * @param requiredFields
     * @param propertyMap
     * @param processedFieldNames
     */
    private void processGetterMethods(CtType<?> clazz, List<String> requiredFields, @Out Map<String, Schema> propertyMap, @Out Set<String> processedFieldNames) {
        boolean detectedAnySimpleFields = !processedFieldNames.isEmpty();

        for (CtMethod<?> method : clazz.getMethods()) {
            // don't consider static methods or methods that are no getter
            if (method.isStatic() || !isGetterMethod(method)) {
                continue;
            }

            String fieldName = convertGetterMethodToPropertyName(method);

            List<CtAnnotation<?>> ctAnnotations = method.getAnnotations();
            Annotation[] annotations = schemaGeneratorHelper.getActualAnnotations(ctAnnotations);

            // if field exists and getter has other @JsonProperty mapping, update schema
            if (processedFieldNames.contains(fieldName)) {
                if (hasJsonIgnore(annotations)) {
                    propertyMap.remove(fieldName);
                    return;
                }

                String jsonPropertyName = tryGetNameFromJsonPropertyAnnotationsOrGetOriginalName(fieldName, annotations);

                if (!jsonPropertyName.equals(fieldName)) {
                    Schema existingSchema = propertyMap.get(fieldName);
                    if (existingSchema != null) {
                        propertyMap.remove(existingSchema.getName());
                        existingSchema.setName(jsonPropertyName);
                        propertyMap.put(jsonPropertyName, existingSchema);
                    }
                }
            } else if (!detectedAnySimpleFields || Arrays.stream(annotations).anyMatch(a -> a instanceof JsonProperty)) { // prioritize fields, but consider methods explicitly marked as JsonProperty
                if (hasJsonIgnore(annotations)) {
                    processedFieldNames.add(fieldName);
                    return;
                }

                getMethodSchema(method, requiredFields).ifPresent(schema -> {
                    schemaFieldInterceptors.forEach(modelClassFieldInterceptor -> modelClassFieldInterceptor.intercept(clazz, method, schema));
                    propertyMap.put(schema.getName(), schema);
                    processedFieldNames.add(fieldName);
                });
            }
        }
    }

    /**
     * Process constructors annotated with @JsonCreator and check for @JsonProperty mappings.
     *
     * @param clazz
     * @param requiredFields
     * @param propertyMap
     * @param processedFieldNames
     */
    private void processJsonCreatorConstructors(CtType<?> clazz, List<String> requiredFields, @Out Map<String, Schema> propertyMap, @Out Set<String> processedFieldNames) {
        if (!(clazz instanceof CtClass<?> ctClass)) {
            return;
        }

        for (CtConstructor<?> constructor : ctClass.getConstructors()) {
            if (!hasJsonCreatorAnnotation(constructor)) {
                continue;
            }

            for (CtParameter<?> param : constructor.getParameters()) {
                List<CtAnnotation<?>> ctAnnotations = param.getAnnotations();
                Annotation[] annotations = schemaGeneratorHelper.getActualAnnotations(ctAnnotations);

                String paramName = param.getSimpleName();
                String jsonPropertyName = tryGetNameFromJsonPropertyAnnotationsOrGetOriginalName(paramName, annotations);

                // if field exists and constructor has other @JsonProperty mapping, update schema
                if (processedFieldNames.contains(paramName)) {
                    if (hasJsonIgnore(annotations)) {
                        propertyMap.remove(paramName);
                        return;
                    }

                    if (!jsonPropertyName.equals(paramName)) {
                        Schema existingSchema = propertyMap.get(paramName);
                        if (existingSchema != null) {
                            propertyMap.remove(existingSchema.getName());
                            existingSchema.setName(jsonPropertyName);
                            propertyMap.put(jsonPropertyName, existingSchema);
                        }
                    }
                } else {
                    if (hasJsonIgnore(annotations)) {
                        processedFieldNames.add(paramName);
                        return;
                    }
                    CtTypeReference<?> typeSignature = param.getType();

                    getFieldOrMethodSchema(jsonPropertyName, typeSignature, ctAnnotations, requiredFields).ifPresent(schema -> {
                        propertyMap.put(schema.getName(), schema);
                        processedFieldNames.add(paramName);
                    });
                }
            }
        }
    }

    private boolean hasJsonCreatorAnnotation(CtConstructor<?> constructor) {
        return constructor.getAnnotations().stream().anyMatch(ann -> ann.getAnnotationType().getQualifiedName().equals("com.fasterxml.jackson.annotation.JsonCreator"));
    }

    private Optional<Schema> getFieldSchema(CtField<?> field, @Out List<String> requiredFields) {
        String simpleName = field.getSimpleName();
        CtTypeReference<?> typeSignature = field.getType();
        List<CtAnnotation<?>> ctAnnotations = field.getAnnotations();

        return getFieldOrMethodSchema(simpleName, typeSignature, ctAnnotations, requiredFields);
    }

    private Optional<Schema> getMethodSchema(CtMethod<?> method, @Out List<String> requiredFields) {
        String simpleName = convertGetterMethodToPropertyName(method);
        CtTypeReference<?> typeSignature = method.getType();
        List<CtAnnotation<?>> ctAnnotations = method.getAnnotations();

        return getFieldOrMethodSchema(simpleName, typeSignature, ctAnnotations, requiredFields);
    }

    private boolean isGetterMethod(CtMethod<?> method) {
        if (method == null || method.getSimpleName() == null)
            return false;

        return method.getSimpleName().startsWith("get")
                || method.getSimpleName().startsWith("is")
                || method.getSimpleName().startsWith("has");
    }

    /**
     * Converts getSomeParam() or isSomeParam() or hasSomeParam() -> "someParam".
     * This method does not handle JsonProperty annotations. This happens during schema generation.
     *
     * @param getterMethod
     * @return
     */
    private String convertGetterMethodToPropertyName(CtMethod<?> getterMethod) {
        if (!isGetterMethod(getterMethod)) {
            return "unknown-param-name";
        }

        String methodName = getterMethod.getSimpleName();

        // remove "prefix"
        String base = "";

        if (methodName.startsWith("get") && methodName.length() > 3) {
            base = methodName.substring(3);
        } else if (methodName.startsWith("is") && methodName.length() > 2) {
            base = methodName.substring(2);
        } else if (methodName.startsWith("has") && methodName.length() > 3) {
            base = methodName.substring(3);
        } else {
            return "unknown-param-name";
        }

        // Lowercase first character of the property name
        return Character.toLowerCase(base.charAt(0)) + base.substring(1);
    }

    // todo extract to SchemaGeneratorHelper
    private Optional<Schema> getFieldOrMethodSchema(String simpleName, CtTypeReference<?> typeSignature, List<CtAnnotation<?>> ctAnnotations, @Out List<String> requiredFields) {
        Annotation[] annotations = schemaGeneratorHelper.getActualAnnotations(ctAnnotations);
        
        simpleName = tryGetNameFromJsonPropertyAnnotationsOrGetOriginalName(simpleName, annotations);

        if (isRequired(annotations)) {
            requiredFields.add(simpleName);
        }

        Optional<Schema> resultSchema;

        if (typeSignature.isPrimitive()) {
            resultSchema = createBaseTypeSchema(typeSignature, requiredFields, annotations);
        } else if (typeSignature.isArray()) {
            resultSchema = createArrayTypeSchema(typeSignature, annotations);
        } else if (StringUtils.equalsIgnoreCase(typeSignature.getQualifiedName(), "java.lang.Object")) {
            ObjectSchema objectSchema = new ObjectSchema();
            objectSchema.setName(simpleName);
            resultSchema = Optional.of(objectSchema);
        } else if (schemaGeneratorHelper.isTypeEquivalent(typeSignature, Set.class)) {
            // if no parameterized types are available the getGenericParam returns null and parseSignature uses object as parameterized type
            CtTypeReference<?> listGenericParameter = schemaGeneratorHelper.getGenericParam(typeSignature);
            resultSchema = Optional.of(schemaGeneratorHelper.parseArraySignature(listGenericParameter, annotations));
            resultSchema.ifPresent(schema -> schema.setUniqueItems(true));
        } else if (schemaGeneratorHelper.isTypeEquivalent(typeSignature, Collection.class)) {
            // if no parameterized types are available the getGenericParam returns null and parseSignature uses object as parameterized type
            CtTypeReference<?> listGenericParameter = schemaGeneratorHelper.getGenericParam(typeSignature);
            resultSchema = Optional.of(schemaGeneratorHelper.parseArraySignature(listGenericParameter, annotations));
        } else if (schemaGeneratorHelper.isTypeEquivalent(typeSignature, Map.class)) {
            // if no parameterized types are available the getGenericParam returns null and parseSignature uses object as parameterized type
            CtTypeReference<?> listGenericParameter = schemaGeneratorHelper.getGenericParamAt(typeSignature, 1); // 0th is always object
            resultSchema = Optional.of(schemaGeneratorHelper.parseDictSignature(listGenericParameter, annotations));
        } else {
            resultSchema = createClassRefSchema(typeSignature, annotations);
        }

        // update the schema name based on the actual name used during de/serialization
        if (resultSchema.isPresent())
            resultSchema.get().setName(simpleName);

        return resultSchema;
    }

    /**
     * Returns True if the annotations contain a @JsonIgnore annotation.
     * @param annotations
     * @return
     */
    private boolean hasJsonIgnore(Annotation[] annotations) {
        return schemaGeneratorHelper.hasJsonIgnore(annotations);
    }

    /**
     * Translate variable names if @JsonProperty annotation exists, or return original name
     *
     * @param originalSimpleName
     * @param annotations
     * @return
     */
    private String tryGetNameFromJsonPropertyAnnotationsOrGetOriginalName(String originalSimpleName, Annotation[] annotations) {
        String newSimpleName = originalSimpleName;

        for (Annotation annotation : annotations) {
            if (annotation instanceof JsonProperty jsonProperty) {
                String jsonPropertyValue = jsonProperty.value();

                if (jsonPropertyValue != null && !jsonPropertyValue.isEmpty()) {
                    logger.info("Found @JsonProperty with value: {}, replacing original field name: {}", jsonPropertyValue, originalSimpleName);
                    newSimpleName = jsonPropertyValue;
                }
            }
        }

        return newSimpleName;
    }


    private Optional<Schema> createClassRefSchema(CtTypeReference<?> typeClass, Annotation[] annotations) {
        Schema<?> schema = schemaGeneratorHelper.parseClassRefTypeSignature(typeClass, annotations);
        schemaGeneratorHelper.enrichWithTypeAnnotations(schema, annotations);
        return Optional.ofNullable(schema);
    }

    private Optional<Schema> createArrayTypeSchema(CtTypeReference<?> typeSignature, Annotation[] annotations) {
        CtTypeReference<?> arrayComponentType = schemaGeneratorHelper.getGenericParam(typeSignature);
        Schema<?> schema = schemaGeneratorHelper.parseArraySignature(arrayComponentType, annotations);
        schemaGeneratorHelper.enrichWithTypeAnnotations(schema, annotations);
        return Optional.ofNullable(schema);
    }

    private Optional<Schema> createBaseTypeSchema(CtTypeReference<?> fieldOrMethodType, @Out List<String> requiredFields, Annotation[] annotations) {
        // TODO iterate through primitive datatypes and remove it from required fields (as they have default values)
        // TODO check - primitive types such as int or boolean have a default value and are not required, also when adding @NotNull
//        if (!requiredFields.contains(field.getSimpleName())) {
//            requiredFields.add(field.getSimpleName());
//         }
        Schema<?> schema = schemaGeneratorHelper.parseBaseTypeSignature(fieldOrMethodType, annotations);
        schemaGeneratorHelper.enrichWithTypeAnnotations(schema, annotations);
        return Optional.ofNullable(schema);
    }

    private boolean isRequired(Annotation[] annotations) {
        return Stream.of(annotations).anyMatch(annotation -> {
                    if (annotation.annotationType() != null) {
                        return validationAnnotationProvider.isNotNullAnnotation(annotation) || validationAnnotationProvider.isNotEmptyAnnotation(annotation);
//                             || (annotation instanceof io.swagger.v3.oas.annotations.media.Schema &&
//                                ((io.swagger.v3.oas.annotations.media.Schema) annotation).required())
                    }
                    return false;
                }
        );
    }

}
