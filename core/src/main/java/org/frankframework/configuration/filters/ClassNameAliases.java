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

/**
 * Class name aliases that libraries and extensions ship with their jar, so they can rename or move a class
 * without breaking the configurations that use the old name.
 * <p>
 * Every {@value #RESOURCE} on the framework's class path is read once and merged. Each line maps an old fully
 * qualified class name to its new one: {@code com.example.OldPipe=com.example.pipes.NewPipe}. When two files map
 * the same name differently, the first one found wins and a warning names both. Aliases are not chained: an alias
 * is applied once. Configuration class loaders are not searched, so a configuration cannot add aliases.
 * </p>
 * The {@link ClassNameRewriter} applies them while a configuration is digested.
 */
public final class ClassNameAliases {

	public static final String RESOURCE = "META-INF/frankframework/class-name-aliases.properties";

	private final Map<String, String> aliases;

	private ClassNameAliases(Map<String, String> aliases) {
		this.aliases = Collections.unmodifiableMap(aliases);
	}

	/** The aliases on the class path of the class loader that loaded the framework. */
	public static ClassNameAliases getInstance() {
		return Holder.INSTANCE;
	}

	/** Reads and merges every {@value #RESOURCE} the given class loader can see. */
	public static ClassNameAliases load(@NonNull ClassLoader classLoader) {
		return new ClassNameAliases(ClassNameMappings.load(classLoader, RESOURCE, "class name alias"));
	}

	/** The class name an old name has been renamed to, if any. */
	public Optional<String> target(String className) {
		return Optional.ofNullable(aliases.get(className));
	}

	public boolean isEmpty() {
		return aliases.isEmpty();
	}

	private static final class Holder {
		private static final ClassNameAliases INSTANCE = load(ClassNameAliases.class.getClassLoader());
	}
}
