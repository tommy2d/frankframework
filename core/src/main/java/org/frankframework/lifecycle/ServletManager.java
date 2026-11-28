/*
   Copyright 2019-2020 Nationale-Nederlanden, 2021-2026 WeAreFrank!

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
package org.frankframework.lifecycle;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.HttpConstraintElement;
import jakarta.servlet.HttpMethodConstraintElement;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.ServletRegistration.Dynamic;
import jakarta.servlet.ServletSecurityElement;
import jakarta.servlet.annotation.ServletSecurity;
import jakarta.servlet.annotation.ServletSecurity.TransportGuarantee;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.web.context.ServletContextAware;

import lombok.Setter;

import org.frankframework.lifecycle.servlets.AbstractServletAuthenticator;
import org.frankframework.lifecycle.servlets.AuthenticationType;
import org.frankframework.lifecycle.servlets.IAuthenticator;
import org.frankframework.lifecycle.servlets.JeeAuthenticator;
import org.frankframework.lifecycle.servlets.NoOpAuthenticator;
import org.frankframework.lifecycle.servlets.SecuritySettings;
import org.frankframework.lifecycle.servlets.ServletConfiguration;
import org.frankframework.lifecycle.servlets.URLRequestMatcher;
import org.frankframework.util.AppConstants;
import org.frankframework.util.ClassUtils;
import org.frankframework.util.EnumUtils;
import org.frankframework.util.LogUtil;
import org.frankframework.util.SpringUtils;
import org.frankframework.util.StringUtil;

/**
 * <p>
 * Enables the use of programmatically adding servlets to the given ServletContext.<br/>
 * Run during the ApplicationServers {@link ServletContextListener#contextInitialized(jakarta.servlet.ServletContextEvent) contextInitialized} phase, before starting servlets.
 * This ensures that all (dynamic) {@link DynamicRegistration.Servlet servlets} are created, before servlets are being created.
 * This in turn avoids a ConcurrentModificationException if this where to be done during a {@link jakarta.servlet.http.HttpServlet servlet} initialization phase.
 * </p>
 * <p>
 * When <code>dtap.stage</code> is set to LOC, the default behavior of each servlet is not-secured (no authentication) on HTTP.<br/>
 * When <code>dtap.stage</code> is NOT set to LOC, the default behavior of each servlet is secured (authentication enforced) on HTTPS.
 * </p>
 * <p>
 * To change this behavior the following properties can be used;
 * <code>servlet.servlet-name.transportGuarantee</code> - forces HTTPS when set to CONFIDENTIAL, or HTTP when set to NONE<br/>
 * <code>servlet.servlet-name.securityRoles</code> - use the default IBIS roles or create your own<br/>
 * <code>servlet.servlet-name.urlMapping</code> - path the servlet listens to<br/>
 * <code>servlet.servlet-name.loadOnStartup</code> - automatically load or use lazy-loading (affects application startup time)<br/>
 * <code>servlet.servlet-name.authenticator</code> - enables authentication on this endpoint<br/>
 * </p>
 * NOTE:
 * Both CONTAINER and NONE are non-configurable default authenticators.
 *
 * @author Niels Meijer
 *
 */
public class ServletManager implements ApplicationContextAware, InitializingBean, ServletContextAware {

	private ServletContext servletContext = null;
	private final List<String> registeredRoles = new ArrayList<>(AbstractServletAuthenticator.DEFAULT_IBIS_ROLES); // Add the default IBIS roles
	private final Logger log = LogUtil.getLogger(this);
	private final Map<String, ServletConfiguration> servlets = new HashMap<>();
	private final Map<String, IAuthenticator> authenticators = new HashMap<>();
	private @Setter ApplicationContext applicationContext;
	private boolean allowUnsecureOptionsRequest = false;

	protected ServletContext getServletContext() {
		return servletContext;
	}

	public List<String> getDeclaredRoles() {
		return Collections.unmodifiableList(registeredRoles);
	}

	/*
	 * Maybe a bit overkill to use ServletContextAware and Autowired but this ensures that the
	 * ServletContext is always set, when running traditional WAR as well as via Spring Boot.
	 */
	@Override
	@Autowired
	@Lazy
	public void setServletContext(@NonNull ServletContext servletContext) {
		this.servletContext = servletContext;
	}

	@Override // After initialization but before other servlets are wired
	public void afterPropertiesSet() {
		if(servletContext == null) {
			throw new IllegalStateException("not ServletContext configured");
		}

		Environment env = applicationContext.getEnvironment();
		SecuritySettings.setupDefaultSecuritySettings(env);
		allowUnsecureOptionsRequest = env.getProperty(AbstractServletAuthenticator.ALLOW_OPTIONS_REQUESTS_KEY, boolean.class, false);

		addDefaultAuthenticator(AuthenticationType.CONTAINER);
		addDefaultAuthenticator(AuthenticationType.NONE);

		resolveAuthenticators();
		log.info("found Authenticators {}", authenticators::values);
	}

	private void addDefaultAuthenticator(AuthenticationType type) {
		if(!authenticators.containsKey(type.name())) {
			Class<? extends IAuthenticator> clazz = type.getAuthenticator();
			IAuthenticator authenticator = SpringUtils.createBean(applicationContext, clazz);
			authenticators.put(type.name(), authenticator);
		}
	}

	public void startAuthenticators() {
		log.info("starting Authenticators {}", authenticators::values);

		for(IAuthenticator authenticator : authenticators.values()) {
			authenticator.build();
		}
	}

	private void resolveAuthenticators() {
		AppConstants.getInstance()
				.getListProperty("application.security.http.authenticators")
				.forEach(this::resolveAndConfigureAuthenticator);
	}

	private void resolveAndConfigureAuthenticator(String authenticatorName) {
		AppConstants appConstants = AppConstants.getInstance();
		String properyPrefix = "application.security.http.authenticators."+authenticatorName+".";
		String type = AppConstants.getInstance().getProperty(properyPrefix+"type");
		AuthenticationType auth;
		try {
			auth = EnumUtils.parse(AuthenticationType.class, type);
		} catch (IllegalArgumentException e) {
			throw new IllegalStateException("invalid authenticator type", e);
		}
		Class<? extends IAuthenticator> clazz = auth.getAuthenticator();
		IAuthenticator authenticator = SpringUtils.createBean(applicationContext, clazz);

		for(Method method: clazz.getMethods()) {
			if(!method.getName().startsWith("set") || method.getParameterTypes().length != 1)
				continue;

			String setter = StringUtil.lcFirst(method.getName().substring(3));
			String value = appConstants.getProperty(properyPrefix+setter);
			if(StringUtils.isEmpty(value))
				continue;

			ClassUtils.invokeSetter(authenticator, method, value);
		}

		authenticators.put(authenticatorName, authenticator);
	}

	/**
	 * Register a new role
	 * @param roleNames String or multiple strings of roleNames to register
	 */
	public void declareRoles(List<String> roleNames) {
		for (String role : roleNames) {
			if(StringUtils.isNotEmpty(role) && !registeredRoles.contains(role)) {
				registeredRoles.add(role);

				getServletContext().declareRoles(role);
				log.info("declared role [{}]", role);
			}
		}
	}

	public void register(ServletConfiguration config) {
		if(!config.isEnabled()) {
			log.info("skip instantiating servlet name [{}] not enabled", config::getName);
			return;
		}

		String prefix = "servlet." + config.getName() + ".";
		List<String> groups = AppConstants.getInstance().getListProperty(prefix + "authenticationGroups");
		String authenticatorName = config.getAuthenticatorName();
		// Preserve existing installations with a named authenticator called GROUPED.
		if ("GROUPED".equals(authenticatorName) && !authenticators.containsKey(authenticatorName)) {
			if (groups.isEmpty()) {
				throw new IllegalArgumentException(prefix + "authenticationGroups is required with authenticator GROUPED");
			}
			registerAuthenticationGroups(config, prefix, groups);
			registerServlet(config);
			return;
		}

		if (!groups.isEmpty()) {
			throw new IllegalArgumentException(prefix + "authenticationGroups requires the GROUPED mode and no named authenticator called GROUPED");
		}
		registerServlet(config);
		if(StringUtils.isNotEmpty(authenticatorName)) {
			IAuthenticator authenticator = authenticators.get(authenticatorName);
			if(authenticator == null) {
				throw new IllegalStateException("unable to configure servlet security, authenticator ["+authenticatorName+"] does not exist");
			}
			authenticator.registerServlet(config);
		}
	}

	/**
	 * Register independent Spring Security chains under a single servlet mapping.
	 * Each group owns one literal path segment. The container still dispatches the
	 * original path to the same servlet. Container authentication additionally
	 * requires a matching security-constraint in the application descriptor.
	 */
	private void registerAuthenticationGroups(ServletConfiguration servlet, String prefix, List<String> groups) {
		List<String> mappings = servlet.getUrlMapping();
		if (mappings.size() != 1 || !mappings.getFirst().endsWith("/*")
				|| !servlet.getSecurityRoles().isEmpty()) {
			throw new IllegalArgumentException("Authentication groups require one prefix mapping and no servlet-wide roles");
		}
		String base = Strings.CS.removeEnd(mappings.getFirst(), "*");
		var groupMappings = new HashSet<String>();
		for (String group : groups) {
			if (!group.matches("[a-zA-Z0-9_-]+") || !groupMappings.add(base + group + "/*")) {
				throw new IllegalArgumentException("Invalid or duplicate authentication group: " + group);
			}
			String settings = prefix + "group." + group + ".";
			String name = applicationContext.getEnvironment().getRequiredProperty(settings + "authenticator");
			IAuthenticator authenticator = authenticators.get(name);
			List<String> roles = AppConstants.getInstance().getListProperty(settings + "securityRoles");
			if ("NONE".equals(name) && authenticator instanceof NoOpAuthenticator) {
				if (!roles.isEmpty()) {
					throw new IllegalArgumentException(settings + "securityRoles must be empty for a public group");
				}
				continue;
			}
			if (authenticator == null || authenticator instanceof NoOpAuthenticator) {
				throw new IllegalArgumentException("Expected a protecting authenticator for group " + group);
			}
			if (roles.isEmpty()) {
				throw new IllegalArgumentException("Missing securityRoles for group " + group);
			}
			ServletConfiguration scope = new ServletConfiguration();
			scope.setUrlMapping(base + group + "/*");
			scope.setSecurityRoles(roles.toArray(String[]::new));
			declareRoles(roles);
			authenticator.registerServlet(scope);
		}
		// Use the same path matcher as the authenticators. Scope the guard to this servlet.
		var knownGroups = new URLRequestMatcher(groupMappings);
		var guard = getServletContext().addFilter(servlet.getName() + "-authentication-groups", (request, response, chain) -> {
			if (knownGroups.matches((HttpServletRequest) request)) {
				chain.doFilter(request, response);
			} else {
				((HttpServletResponse) response).setStatus(HttpServletResponse.SC_FORBIDDEN);
			}
		});
		guard.setAsyncSupported(true);
		guard.addMappingForServletNames(EnumSet.allOf(DispatcherType.class), false, servlet.getName());
		servlet.setAuthenticationGroups(List.copyOf(groups));
	}

	private void registerServlet(ServletConfiguration config) {
		String servletName = config.getName();
		if(servlets.containsKey(servletName)) {
			throw new IllegalArgumentException("unable to instantiate servlet ["+servletName+"], servlet name must be unique");
		}

		log.debug("instantiating IbisInitializer servlet name [{}] servletClass [{}]", servletName, config.getServlet());

		ServletRegistration.Dynamic serv = getServletContext().addServlet(servletName, config.getServlet());

		serv.setLoadOnStartup(config.getLoadOnStartup());
		serv.addMapping(getEndpoints(config.getUrlMapping()));
		declareRoles(config.getSecurityRoles());
		serv.setServletSecurity(getServletSecurity(config));

		if(!config.getInitParameters().isEmpty()) {
			// Manually loop through the map as serv.setInitParameters will fail all parameters even if only 1 fails...
			for(Entry<String, String> entry : config.getInitParameters().entrySet()) {
				String key = entry.getKey();
				String value = entry.getValue();
				if(!serv.setInitParameter(key, value)) {
					log("unable to set init-parameter ["+key+"] with value ["+value+"] for servlet ["+servletName+"]", Level.ERROR);
				}
			}
		}

		servlets.put(servletName, config);
		logServletInfo(serv, config);
	}

	// Remove all endpoint excludes
	private String[] getEndpoints(List<String> list) {
		return list.stream()
				.filter(e -> e.charAt(0) != '!')
				.toArray(String[]::new);
	}

	private void logServletInfo(Dynamic serv, ServletConfiguration config) {
		StringBuilder builder = new StringBuilder("registered");
		builder.append(" servlet [").append(serv.getName()).append("]");
		builder.append(" configuration ");
		builder.append(config);

		getServletContext().log(builder.toString());

		if(log.isDebugEnabled()) builder.append(" class [").append(serv.getClassName()).append("]");
		log.info(builder::toString);
	}

	private ServletSecurityElement getServletSecurity(ServletConfiguration config) {
		String[] roles = new String[0];
		if(config.isAuthenticationEnabled() && authenticators.get(config.getAuthenticatorName()) instanceof JeeAuthenticator) {
			// Only set roles when using Container Based Authentication, else let Spring handle it.
			roles = config.getSecurityRoles().toArray(new String[0]);
		}
		HttpConstraintElement httpConstraintElement = new HttpConstraintElement(config.getTransportGuarantee(), roles);

		List<HttpMethodConstraintElement> methodConstraints = new ArrayList<>();
		if(allowUnsecureOptionsRequest) {
			methodConstraints.add(new HttpMethodConstraintElement("OPTIONS"));
		}
		return new ServletSecurityElement(httpConstraintElement, methodConstraints);
	}

	private void log(String msg, Level level) {
		if(log.isInfoEnabled() )
			getServletContext().log(msg);

		log.log(level, msg);
	}

	public ServletConfiguration getServlet(String name) {
		return servlets.get(name);
	}

	public Collection<ServletConfiguration> getServlets() {
		return Collections.unmodifiableCollection(servlets.values());
	}

	public static ServletSecurity.TransportGuarantee getTransportGuarantee(String propertyName) {
		AppConstants appConstants = AppConstants.getInstance();
		String constraintType = appConstants.getString(propertyName, null);
		if (StringUtils.isNotEmpty(constraintType)) {
			return EnumUtils.parse(TransportGuarantee.class, constraintType);
		}
		return SecuritySettings.getDefaultTransportGuarantee();
	}
}
