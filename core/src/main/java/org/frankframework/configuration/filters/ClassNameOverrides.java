/*
   Copyright 2026 WeAreFrank!

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package org.frankframework.configuration.filters;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.NonNull;

import lombok.extern.log4j.Log4j2;

/**
 * Class name overrides that a deployment ships with its own jar, to replace a framework or library class with
 * another implementation. Unlike {@link ClassNameAliases} nothing has been renamed: the configuration is correct
 * and its author is not asked to change anything, so no deprecation warning is added.
 * <p>
 * An override is for a difference of insight rather than a rename, for example replacing a class whose behaviour
 * a deployment considers unsafe with a stricter one of its own. Each line maps the class a configuration names to
 * the class to build instead: {@code org.frankframework.senders.LocalFileSystemSender=com.example.StrictSender}.
 * </p>
 * <p>
 * Every {@value #RESOURCE} on the framework class path is read once and merged, and the active overrides are
 * logged once at startup, because substituting the class a configuration names is invisible to its author and an
 * operator has to be able to see that it happened. When two files map the same name differently the first one
 * found wins and a warning names both.
 * </p>
 * <p>
 * Configuration class loaders are not searched. That is what keeps this from being a way for a configuration to
 * choose its own implementation classes, so it is a security boundary rather than a convenience.
 * </p>
 */
@Log4j2
public final class ClassNameOverrides {

	public static final String RESOURCE = "META-INF/frankframework/class-name-overrides.properties";

	private final Map<String, String> overrides;

	private ClassNameOverrides(Map<String, String> overrides) {
		this.overrides = Collections.unmodifiableMap(overrides);
	}

	/** The overrides on the class path of the class loader that loaded the framework. */
	public static ClassNameOverrides getInstance() {
		return Holder.INSTANCE;
	}

	/** No overrides at all, used when they are switched off. */
	public static ClassNameOverrides none() {
		return new ClassNameOverrides(Map.of());
	}

	/** Reads and merges every {@value #RESOURCE} the given class loader can see. */
	public static ClassNameOverrides load(@NonNull ClassLoader classLoader) {
		Map<String, String> merged = ClassNameMappings.load(classLoader, RESOURCE, "class name override");
		merged.forEach((from, to) -> log.info("class [{}] is overridden by [{}]", from, to));
		return new ClassNameOverrides(merged);
	}

	/** The class to build instead of the given one, if any. */
	public Optional<String> target(String className) {
		return Optional.ofNullable(overrides.get(className));
	}

	public boolean isEmpty() {
		return overrides.isEmpty();
	}

	private static final class Holder {
		private static final ClassNameOverrides INSTANCE = load(ClassNameOverrides.class.getClassLoader());
	}
}
