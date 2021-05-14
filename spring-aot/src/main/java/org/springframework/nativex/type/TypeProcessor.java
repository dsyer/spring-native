/*
 * Copyright 2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.nativex.type;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.nativex.domain.init.InitializationDescriptor;
import org.springframework.nativex.domain.proxies.ProxyDescriptor;
import org.springframework.nativex.hint.AccessBits;
import org.springframework.nativex.hint.Flag;

/**
 * The {@link TypeProcessor} traverses reachable structures from a given root type by inspecting the type itself,
 * its annotations, fields and their annotations, constructors & methods as well as their arguments & annotations.
 * Whenever the processor discovers a {@link Type} (might as well be an annotation) it has not seen before it invokes a
 * callback function providing the {@link Type} and a {@link NativeContext} to register configuration in.
 * For usage within {@link org.springframework.nativex.type.NativeConfiguration#computeHints} the {@link TypeProcessor}
 * uses its own {@link NativeContext} that captures interactions to provide a {@link List} of {@link HintDeclaration hints}
 * when done processing.
 * <pre class="code">
 * TypeProcessor processor = TypeProcessor.namedProcessor("a prefix used for logging")
 * 			.skipFieldInspection()
 * 			.includeAnnotationsMatching(annotation -> annotation.isPartOfDomain("org.springframework.web.bind.annotation"))
 * 			.onTypeDiscovered((type, registrar) -> registrar.addReflectiveAccess(type, new AccessDescriptor(AccessBits.FULL_REFLECTION)))
 * 			.onAnnotationDiscovered((annotation, registrar) -> registrar.addReflectiveAccess(annotation, new AccessDescriptor(AccessBits.ANNOTATION)));
 * List&lt;HintDeclaration&gt; hints = processor.use(typeSystem)
 * 			.toProcessTypes(ts -> ts.findTypesAnnotated("...", true).stream().map(ts::resolveName));
 * </pre>
 * When used within a {@link org.springframework.nativex.type.ComponentProcessor} implementation the {@link TypeProcessor}
 * can directly write the configuration to the given {@link NativeContext}.
 * <pre class="code">
 * Type domainType = nativeContext.getTypeSystem().resolveName(componentType);
 * processor.use(nativeContext)
 * 			.toProcessType(domainType);
 * </pre>
 * Use {@literal include/exclude} filters to limit the scope of the type inspection.
 * Synthetic {@link Field Fields} as well as {@link Field Fields} and {@link Method Methods} named {@code $$_hibernate}
 * are filtered out by default. So are all annotations from {@literal java.lang.annotation}, {@literal java.lang.Object}
 * itself and all types from {@literal sun}, {@literal jdk} and the {@literal org.hibernate.engine} namespace.
 *
 * @author Christoph Strobl
 */
public class TypeProcessor {

	private BiConsumer<Type, NativeContext> typeRegistrar;
	private BiConsumer<Type, NativeContext> annotationRegistrar;

	private BiPredicate<Type, NativeContext> typeFilter;
	private BiPredicate<Type, Method> ctorFilter = (owner, method) -> method.getName().equals("<init>");
	private BiPredicate<Type, Method> methodFilter = (owner, method) -> {

		if (method.getName().startsWith("$$_hibernate")) {
			return false;
		}
		return true;
	};

	private BiPredicate<Type, Field> fieldFilter = (owner, field) -> {

		if (field.isSynthetic() || field.getName().startsWith("$$_hibernate")) {
			return false;
		}
		return true;
	};

	private Predicate<Type> annotationFilter = (annotation) -> !annotation.isPartOfDomain("java.lang.annotation");

	private InspectionFilter inspectionFilter = new DefaultInspectionFilter();

	private String componentLogName = "TypeProcessor";

	/**
	 * Get the default configuration that uses {@link AccessBits#FULL_REFLECTION} for types and {@link AccessBits#ANNOTATION} for discovered annotations.
	 * Override the defaults via {@link #onTypeDiscovered(BiConsumer)} and {@link #onAnnotationDiscovered(BiConsumer)}.
	 *
	 * @return new instance of {@link TypeProcessor}.
	 */
	public static TypeProcessor namedProcessor(String componentLogName) {
		return new TypeProcessor(new AccessDescriptor(AccessBits.FULL_REFLECTION), new AccessDescriptor(AccessBits.ANNOTATION)).named(componentLogName);
	}

	/**
	 * @param typeAccessDescriptor the {@link AccessDescriptor} to use for types.
	 * @param annotationAccessDescriptor the {@link AccessDescriptor} to use for annotations.
	 */
	public TypeProcessor(AccessDescriptor typeAccessDescriptor, AccessDescriptor annotationAccessDescriptor) {
		this((type, context) -> {

			context.log(String.format("TypeProcessor - Registering %s with access %s.", type.getName(), typeAccessDescriptor));
			context.addReflectiveAccess(type, typeAccessDescriptor);
		}, (type, context) -> {

			context.log(String.format("TypeProcessor - Registering %s with access %s.", type.getName(), annotationAccessDescriptor));
			context.addReflectiveAccess(type, annotationAccessDescriptor);
		});
	}

	/**
	 * @param typeRegistrar callback function to potentially register configuration for types.
	 * @param annotationRegistrar callback function to potentially register configuration for annotations.
	 */
	public TypeProcessor(BiConsumer<Type, NativeContext> typeRegistrar, BiConsumer<Type, NativeContext> annotationRegistrar) {
		this((type, context) -> !TypeProcessor.isExcludedByDefault(type), typeRegistrar, annotationRegistrar);
	}

	/**
	 * @param typeFilter top level type filter - overrides potential defaults.
	 * @param typeRegistrar callback function to potentially register configuration for types.
	 * @param annotationRegistrar callback function to potentially register configuration for annotations.
	 */
	public TypeProcessor(BiPredicate<Type, NativeContext> typeFilter, BiConsumer<Type, NativeContext> typeRegistrar, BiConsumer<Type, NativeContext> annotationRegistrar) {

		this.typeFilter = typeFilter;
		onTypeDiscovered(typeRegistrar);
		onAnnotationDiscovered(annotationRegistrar);
	}

	/**
	 * Define the prefix for log entries.
	 *
	 * @param componentLogName the prefix used when logging (default is 'DomainTypeProcessor').
	 * @return this.
	 */
	public TypeProcessor named(String componentLogName) {

		this.componentLogName = componentLogName;
		return this;
	}

	/**
	 * Include {@link Method methods} matching the given {@link Predicate}.
	 *
	 * @param includeFilter must not be {@literal null}.
	 * @return this.
	 */
	public TypeProcessor filterMethods(Predicate<Method> includeFilter) {
		return filterMethods((owner, method) -> includeFilter.test(method));
	}

	/**
	 * Include {@link Method methods} based on their owning {@link Type} matching the given {@link BiPredicate}.
	 *
	 * @param includeFilter must not be {@literal null}.
	 * @return this.
	 */
	public TypeProcessor filterMethods(BiPredicate<Type, Method> includeFilter) {

		this.methodFilter = this.methodFilter.and(includeFilter);
		return this;
	}

	/**
	 * Skip {@link Method methods} matching the given {@link Predicate}.
	 *
	 * @param excludeFilter must not be {@literal null}.
	 * @return this.
	 */
	public TypeProcessor skipMethodsMatching(Predicate<Method> excludeFilter) {
		return filterMethods(excludeFilter.negate());
	}

	/**
	 * Skip {@link Method methods} based on their owning {@link Type} matching the given {@link BiPredicate}.
	 *
	 * @param excludeFilter must not be {@literal null}.
	 * @return this.
	 */
	public TypeProcessor skipMethodsMatching(BiPredicate<Type, Method> excludeFilter) {
		return filterMethods(excludeFilter.negate());
	}

	/**
	 * Don't look at {@link Method methods} at all. Constructors will still be considered.
	 *
	 * @return this.
	 */
	public TypeProcessor skipMethodInspection() {

		this.methodFilter = (owner, method) -> false;
		return this;
	}

	/**
	 * Include {@link Field fields} matching the given {@link Predicate}.
	 *
	 * @param includeFilter must not be {@literal null}.
	 * @return this.
	 */
	public TypeProcessor filterFields(Predicate<Field> includeFilter) {
		return filterFields((owner, field) -> includeFilter.test(field));
	}

	/**
	 * Include {@link Field fields} based on their owning {@link Type} matching the given {@link BiPredicate}.
	 *
	 * @param includeFilter must not be {@literal null}.
	 * @return this.
	 */
	public TypeProcessor filterFields(BiPredicate<Type, Field> includeFilter) {

		this.fieldFilter = this.fieldFilter.and(includeFilter);
		return this;
	}

	/**
	 * Exclude {@link Field fields} matching the given {@link Predicate}.
	 *
	 * @param excludeFilter must not be {@literal null}.
	 * @return this.
	 */
	public TypeProcessor skipFieldsMatching(Predicate<Field> excludeFilter) {
		return filterFields(excludeFilter.negate());
	}

	/**
	 * Exclude {@link Field fields} based on their owning {@link Type} matching the given {@link BiPredicate}.
	 *
	 * @param excludeFilter must not be {@literal null}.
	 * @return this.
	 */
	public TypeProcessor skipFieldsMatching(BiPredicate<Type, Field> excludeFilter) {
		return filterFields(excludeFilter.negate());
	}

	/**
	 * Don't look at {@link Field fields} at all.
	 *
	 * @return this.
	 */
	public TypeProcessor skipFieldInspection() {

		this.fieldFilter = (owner, field) -> false;
		return this;
	}

	/**
	 * @param includeFilter
	 * @return this.
	 */
	public TypeProcessor filterAnnotations(Predicate<Type> includeFilter) {

		this.annotationFilter = this.annotationFilter.and(includeFilter);
		return this;
	}

	/**
	 * @param excludeFilter
	 * @return this.
	 */
	public TypeProcessor skipAnnotationsMatching(Predicate<Type> excludeFilter) {
		return filterAnnotations(excludeFilter.negate());
	}

	/**
	 * Don't look at {@link Type annotations} at all.
	 *
	 * @return this.
	 */
	public TypeProcessor skipAnnotationInspection() {

		this.annotationFilter = (type) -> false;
		return this;
	}

	public TypeProcessor skipTypesMatching(Predicate<Type> excludeFilter) {

		this.typeFilter = this.typeFilter.and(((BiPredicate<Type, NativeContext>) (type, context) -> excludeFilter.test(type)).negate());
		return this;
	}

	/**
	 * @param includeFilter
	 * @return this.
	 */
	TypeProcessor filterConstructors(Predicate<Method> includeFilter) {

		this.ctorFilter = this.ctorFilter.and((type, ctor) -> includeFilter.test(ctor));
		return this;
	}

	/**
	 * @param includeFilter
	 * @return this.
	 */
	TypeProcessor filterConstructors(BiPredicate<Type, Method> includeFilter) {

		this.ctorFilter = this.ctorFilter.and(includeFilter);
		return this;
	}

	/**
	 * @return this.
	 */
	public TypeProcessor skipConstructorInspection() {

		this.ctorFilter = (type, method) -> false;
		return this;
	}

	/**
	 * Define behavior when a type has been discovered.
	 *
	 * @param typeRegistrar must not be {@literal null}.
	 * @return this.
	 */
	public TypeProcessor onTypeDiscovered(BiConsumer<Type, NativeContext> typeRegistrar) {

		this.typeRegistrar = typeRegistrar;
		return this;
	}

	/**
	 * Define behavior when an annotation has been discovered.
	 *
	 * @param annotationRegistrar must not be {@literal null}.
	 * @return this.
	 */
	public TypeProcessor onAnnotationDiscovered(BiConsumer<Type, NativeContext> annotationRegistrar) {

		this.annotationRegistrar = annotationRegistrar;
		return this;
	}

	/**
	 * Within a {@link org.springframework.nativex.type.ComponentProcessor} - Use a given {@link NativeContext}
	 * for a set of operations that remembers {@link Type types} already inspected.
	 * Reflection-, Proxy-, Resource configuration is registered directly in the {@link NativeContext}.
	 *
	 * @param nativeContext must not be {@literal null}.
	 * @return new instance of {@link TypeCapturingProcessor}.
	 */
	public TypeCapturingProcessor use(NativeContext nativeContext) {

		return new TypeCapturingProcessor() {

			private final Set<Type> seen = new LinkedHashSet<>();

			@Override
			public void toProcessType(Type type) {
				process(type, nativeContext, seen);
			}

			@Override
			public void toProcessTypes(Function<TypeSystem, Stream<Type>> lookup) {
				lookup.apply(nativeContext.getTypeSystem()).forEach(this::toProcessType);
			}
		};
	}

	/**
	 * Within {@link org.springframework.nativex.type.NativeConfiguration#computeHints(TypeSystem)} - Use a given {@link TypeSystem}
	 * for a set of operations that remembers {@link Type types} already inspected.
	 * Reflection-, Proxy-, Resource configuration that is registered in the {@link NativeContext} is collected and returned when
	 * processing is finished.
	 *
	 * @param typeSystem must not be {@literal null}.
	 * @return new instance of {@link TypeHintCreatingProcessor}.
	 */
	public TypeHintCreatingProcessor use(TypeSystem typeSystem) {

		return new TypeHintCreatingProcessor() {

			private final Set<Type> seen = new LinkedHashSet<>();
			private final TypeHintConvertingNativeContext nativeContext = new TypeHintConvertingNativeContext(typeSystem);

			@Override
			public List<HintDeclaration> toProcessTypes(Function<TypeSystem, Stream<Type>> lookup) {

				lookup.apply(nativeContext.getTypeSystem()).forEach(type -> process(type, nativeContext, seen));
				return nativeContext.getHints();
			}

			@Override
			public List<HintDeclaration> toProcessType(Type type) {

				process(type, nativeContext, seen);
				return nativeContext.getHints();
			}
		};
	}

	/**
	 * Process a given {@link Type} by iterating of its direct annotations, fields and the annotations on
	 * them as well as matching methods, invoking the type and annotation registrar when needed.
	 * In case you need to perform a lookup on the type system to provide the types to process call {@link #use(NativeContext)}
	 * <pre class="code">
	 * processor.use(context)
	 *   .toProcessTypes((typeSystem) -> {
	 *       return typeSystem.findTypesAnnotated("Ljavax/persistent/Entity;", true)
	 *         .stream()
	 *         .map(typeSystem::resolveName);
	 *   })
	 * </pre>
	 *
	 * @param domainType can be {@literal null}.
	 * @param imageContext must not be {@literal null}.
	 */
	public void process(Type domainType, NativeContext imageContext) {
		process(domainType, imageContext, new LinkedHashSet<>());
	}

	private void process(Type domainType, NativeContext imageContext, Set<Type> seen) {

		if (domainType == null) {
			return;
		}

		if (!typeFilter.test(domainType, imageContext) || seen.contains(domainType)) {

			imageContext.log(String.format(componentLogName + ": skipping type %s %s.", domainType.getDottedName(), seen.contains(domainType) ? "because it was already processed" : "because it was filtered out by the typeFilter"));
			return;
		}

		imageContext.log(String.format(componentLogName + ": processing type %s.", domainType.getDottedName()));

		// cycle guard
		seen.add(domainType);

		// call method that will add a domain type if necessary.
		typeRegistrar.accept(domainType, imageContext);

		if (inspectionFilter.isExcluded(domainType)) {
			imageContext.log(String.format(componentLogName + ": skip field and method inspection for type %s.", domainType.getDottedName()));
			return;
		}

		processSignatureTypesOfType(domainType, imageContext, seen);

		// inspect annotations on the type itself and register those if necessary.
		processAnnotationsOnType(domainType, imageContext, seen);

		// inspect the constructor and its parameters
		processConstructorsOfType(domainType, imageContext, seen);

		// inspect fields and register the types if necessary.
		processFieldsOfType(domainType, imageContext, seen);

		// inspect methods and register return types if necessary.
		processMethodsOfType(domainType, imageContext, seen);
	}

	private void processSignatureTypesOfType(Type domainType, NativeContext imageContext, Set<Type> seen) {

		domainType.getTypesInSignature()
				.stream()
				.map(it -> imageContext.getTypeSystem().resolve(it, true))
				.filter(Objects::nonNull)
				.forEach(it -> process(it, imageContext, seen));
	}

	private void processMethodsOfType(Type domainType, NativeContext imageContext, Set<Type> seen) {

		domainType.getMethods(method -> methodFilter.test(domainType, method))
				.forEach(it -> processMethod(domainType, it, imageContext, seen));
	}

	private void processConstructorsOfType(Type domainType, NativeContext imageContext, Set<Type> seen) {

		domainType.getMethods(ctor -> ctorFilter.test(domainType, ctor))
				.forEach(it -> processMethod(domainType, it, imageContext, seen));
	}

	private void processMethod(Type type, Method method, NativeContext imageContext, Set<Type> seen) {

		imageContext.log(String.format(componentLogName + ": inspecting %s %s of %s", ctorFilter.test(type, method) ? "constructor" : "method", method, type.getDottedName()));

		method.getSignatureTypes(true).forEach(returnType -> process(returnType, imageContext, seen));
		method.getParameterTypes().forEach(parameterType -> process(parameterType, imageContext, seen));
		method.getAnnotationTypes().forEach(annotation -> processAnnotation(annotation, imageContext, seen));

		for (int parameterIndex = 0; parameterIndex < method.getParameterCount(); parameterIndex++) {
			method.getParameterAnnotationTypes(parameterIndex)
					.forEach(it -> processAnnotation(it, imageContext, seen));
		}
	}

	private void processFieldsOfType(Type domainType, NativeContext imageContext, Set<Type> seen) {

		domainType.getFields().forEach(field -> {

			if (!fieldFilter.test(domainType, field)) {
				imageContext.log(String.format(componentLogName + ": skipping field %s of %s", field.getName(), domainType.getDottedName()));
			} else {

				imageContext.log(String.format(componentLogName + ": inspecting field %s of %s", field.getName(), domainType.getDottedName()));

				field.getTypesInSignature().stream().map(it -> imageContext.getTypeSystem().resolve(it, true))
						.filter(Objects::nonNull)
						.forEach(signatureType -> process(signatureType, imageContext, seen));

				field.getAnnotationTypes().forEach(annotation -> {
					processAnnotation(annotation, imageContext, seen);
				});
			}
		});
	}

	private void processAnnotationsOnType(Type type, NativeContext imageContext, Set<Type> seen) {
		type.getAnnotations().forEach(it -> processAnnotation(it, imageContext, seen));
	}

	private void processAnnotation(Type annotation, NativeContext imageContext, Set<Type> seen) {

		if (seen.contains(annotation) || !imageContext.getTypeSystem().canResolve(annotation.getName()) || !annotationFilter.test(annotation)) {

			String reason = "because it cannot be reached via TypeSystem";
			if (annotation.isPartOfDomain("java.lang.annotation")) {
				reason = "because it is a java.lang.annotation";
			} else if (seen.contains(annotation)) {
				reason = "because it has already been processed";
			}

			imageContext.log(String.format(componentLogName + ": skipping annotation inspection for %s %s", annotation.getDottedName(), reason));
			return;
		}

		imageContext.log(String.format(componentLogName + ": inspecting annotation %s", annotation.getDottedName()));

		// cycle guard
		seen.add(annotation);

		annotationRegistrar.accept(annotation, imageContext);

		// TODO: do we need to check methods on annotations?

		// meta annotations
		processAnnotationsOnType(annotation, imageContext, seen);
	}

	public String getComponentLogName() {
		return componentLogName;
	}

	public static boolean isExcludedByDefault(Type type) {
		return type.getDottedName().equals("java.lang.Object") || type.isPartOfDomain("org.hibernate.engine") || type.isPartOfDomain("sun") || type.isPartOfDomain("jdk.");
	}

	/**
	 * Scan the given {@link TypeSystem} (UserCodeDirectories & SpringJars) for {@link Type types} matching the given {@link Predicate}.
	 *
	 * @param typeSystem must not be {@literal null}.
	 * @param filter must not be {@literal null}.
	 * @return never {@literal null}.
	 */
	private static Stream<Type> scanForTypes(TypeSystem typeSystem, Predicate<Type> filter) {

		return typeSystem.findUserCodeDirectoriesAndSpringJars(typeSystem.getClasspath())
				.flatMap(typeSystem::findClasses)
				.map(typeSystem::typenameOfClass)
				.map(typeSystem::resolveSlashed)
				.filter(filter);
	}

	public interface TypeCapturingProcessor {

		/**
		 * Process a multiple {@link Type types}.
		 *
		 * @param types must not be {@literal null}.
		 */
		default void toProcessTypes(Iterable<Type> types) {
			toProcessTypes(StreamSupport.stream(types.spliterator(), false));
		}

		/**
		 * Process a multiple {@link Type types}.
		 *
		 * @param types must not be {@literal null}.
		 */
		default void toProcessTypes(Stream<Type> types) {
			toProcessTypes(typeSystem -> types);
		}

		/**
		 * Process a multiple {@link Type types} that match a given filter criteria.
		 *
		 * @param filter must not be {@literal null}.
		 */
		default void toProcessTypesMatching(Predicate<Type> filter) {
			toProcessTypes(typeSystem -> TypeProcessor.scanForTypes(typeSystem, filter));
		}

		/**
		 * Use a lookup {@link Function} to provide a {@link java.util.stream.Stream} of {@link Type types}.
		 *
		 * @param lookup must not be {@literal null}.
		 */
		void toProcessTypes(Function<TypeSystem, Stream<Type>> lookup);

		/**
		 * Process a single {@link Type}.
		 *
		 * @param type must not be {@literal null}.
		 */
		void toProcessType(Type type);
	}

	public interface TypeHintCreatingProcessor {

		/**
		 * Process a multiple {@link Type types}.
		 *
		 * @param types must not be {@literal null}.
		 * @return the {@link List} of {@link HintDeclaration hints} to apply.
		 */
		default List<HintDeclaration> toProcessTypes(Iterable<Type> types) {
			return toProcessTypes(StreamSupport.stream(types.spliterator(), false));
		}

		/**
		 * Process a multiple {@link Type types}.
		 *
		 * @param types must not be {@literal null}.
		 */
		default List<HintDeclaration> toProcessTypes(Stream<Type> types) {
			return toProcessTypes(typeSystem -> types);
		}

		/**
		 * Process a multiple {@link Type types} that match a given filter {@link Predicate}.
		 *
		 * @param filter must not be {@literal null}.
		 * @return the {@link List} of {@link HintDeclaration hints} to apply.
		 */
		default List<HintDeclaration> toProcessTypesMatching(Predicate<Type> filter) {
			return toProcessTypes(typeSystem -> TypeProcessor.scanForTypes(typeSystem, filter));
		}

		/**
		 * Use a lookup {@link Function} to provide a {@link java.util.stream.Stream} of {@link Type types}.
		 *
		 * @param lookup must not be {@literal null}.
		 * @return the {@link List} of {@link HintDeclaration hints} to apply.
		 */
		List<HintDeclaration> toProcessTypes(Function<TypeSystem, Stream<Type>> lookup);

		/**
		 * Process a single {@link Type}.
		 *
		 * @param type must not be {@literal null}.
		 * @return the {@link List} of {@link HintDeclaration hints} to apply.
		 */
		List<HintDeclaration> toProcessType(Type type);
	}

	public interface InspectionFilter extends Predicate<Type> {

		default boolean isExcluded(Type type) {
			return !test(type);
		}
	}

	static class DefaultInspectionFilter implements InspectionFilter {

		private final Set<String> excludedDomains;

		public DefaultInspectionFilter() {
			this(new LinkedHashSet<>(Arrays.asList("java.", "sun.", "jdk.", "reactor.")));
		}

		DefaultInspectionFilter(Set<String> excludedDomains) {
			this.excludedDomains = excludedDomains;
		}

		@Override
		public boolean test(Type type) {

			return !excludedDomains
					.stream()
					.anyMatch(type::isPartOfDomain);
		}
	}

	static class TypeHintConvertingNativeContext implements NativeContext {

		private static Log logger = LogFactory.getLog(TypeHintConvertingNativeContext.class);

		private final TypeSystem typeSystem;

		private final List<HintDeclaration> buildTimeHints = new ArrayList<>();
		private final List<HintDeclaration> proxyHints = new ArrayList<>();
		private final List<HintDeclaration> reflectionHints = new ArrayList<>();
		private final List<HintDeclaration> resourceHints = new ArrayList<>();

		TypeHintConvertingNativeContext(TypeSystem typeSystem) {
			this.typeSystem = typeSystem;
		}

		@Override
		public boolean addProxy(List<String> interfaces) {

			HintDeclaration hintDeclaration = new HintDeclaration();
			hintDeclaration.addProxyDescriptor(new ProxyDescriptor(interfaces));
			proxyHints.add(hintDeclaration);
			return true;
		}

		@Override
		public boolean addProxy(String... interfaces) {
			return addProxy(Arrays.asList(interfaces));
		}

		@Override
		public TypeSystem getTypeSystem() {
			return typeSystem;
		}

		@Override
		public void addReflectiveAccess(String key, Flag... flags) {
			addReflectiveAccess(key, new AccessDescriptor(AccessBits.fromFlags(flags).getValue()));
		}

		@Override
		public void addReflectiveAccess(String typeName, AccessDescriptor descriptor) {

			HintDeclaration hintDeclaration = new HintDeclaration();
			hintDeclaration.addDependantType(typeName, descriptor);
			if (requiresResourceAccess(descriptor)) {

				String resourceName = TypeName.fromClassName(typeName.replace("[]", "")).toSlashName();
				if (!resourceName.endsWith(".class")) {
					resourceName = resourceName + ".class";
				}
				hintDeclaration.addResourcesDescriptor(new ResourcesDescriptor(new String[]{resourceName}, false));
			}
			reflectionHints.add(hintDeclaration);
		}

		@Override
		public Set<String> addReflectiveAccessHierarchy(String typename, int accessBits) {

			Type type = typeSystem.resolveDotted(typename, true);
			Set<String> added = new TreeSet<>();
			registerHierarchy(type, added, accessBits);
			return added;
		}

		private boolean requiresResourceAccess(AccessDescriptor accessDescriptor) {
			return accessDescriptor.getAccessBits() != null && AccessBits.isSet(AccessBits.RESOURCE, accessDescriptor.getAccessBits());
		}

		private void registerHierarchy(Type type, Set<String> visited, int accessBits) {

			String typename = type.getDottedName();
			if (visited.add(typename)) {
				addReflectiveAccess(typename, new AccessDescriptor(accessBits));
				Set<String> relatedTypes = type.getTypesInSignature();
				for (String relatedType : relatedTypes) {
					Type t = typeSystem.resolveSlashed(relatedType, true);
					if (t != null) {
						registerHierarchy(t, visited, accessBits);
					}
				}
			}
		}

		@Override
		public boolean hasReflectionConfigFor(String typename) {
			return reflectionHints.stream().anyMatch(hint -> hint.getDependantTypes().containsKey(typename));
		}

		@Override
		public void initializeAtBuildTime(Type type) {

			InitializationDescriptor initializationDescriptor = new InitializationDescriptor();
			initializationDescriptor.addBuildtimeClass(type.getDottedName());

			HintDeclaration hintDeclaration = new HintDeclaration();
			hintDeclaration.addInitializationDescriptor(initializationDescriptor);
			buildTimeHints.add(hintDeclaration);
		}

		@Override
		public void log(String string) {
			logger.debug(string);
		}

		@Override
		public void addResourceBundle(String string) {

			HintDeclaration hintDeclaration = new HintDeclaration();
			ResourcesDescriptor resourcesDescriptor = new ResourcesDescriptor(new String[]{string}, true);
			hintDeclaration.addResourcesDescriptor(resourcesDescriptor);
			resourceHints.add(hintDeclaration);
		}

		List<HintDeclaration> getHints() {

			List<HintDeclaration> target = new ArrayList<>();
			target.addAll(buildTimeHints);
			target.addAll(resourceHints);
			target.addAll(reflectionHints);
			target.addAll(proxyHints);
			return target;
		}
	}
}