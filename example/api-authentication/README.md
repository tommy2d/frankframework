# One Frank instance, multiple API authentication schemes

One Tomcat, one Frank application, one API servlet and one host port (8180) serve every route below. Both Frank configurations load together. The example uses the authentication-group support in **this checkout**. Rebuild the local image after applying the framework changes.

## Run

From the repository root:

```sh
./mvnw -Pdocker,Tomcat -pl docker/Tomcat/webapp -am -DskipTests \
  -Dconfig.image.tag=hello-world-frankframework:local clean package
cd example/api-authentication
python3 init-local.py
docker compose up -d --wait
```

If port 8180 is occupied, export `FRANK_HTTP_PORT=8181` before starting Compose and running `check-local.py`. Use that port in the example URLs and registered OIDC callback.

The initializer reuses existing credentials in the private, Git-ignored directory and assembles the aliases in `private/runtime.properties`. Rerun it and recreate the container after editing the OIDC client credentials. Compose publishes only the loopback interface. Use TLS when deploying network-facing credential endpoints.

## Local web console

Open `/iaf/gui/` on the published port and sign in with username `admin` and password `frank-local`. The Compose file sets these fixed credentials for this loopback-only example.

## Routing and verification

[DeploymentSpecifics.properties](DeploymentSpecifics.properties) assigns positive path segments to independent Spring Security chains. For example, group `basic` matches `/api/basic/*`, using the `basic` authenticator. The servlet itself keeps `/api/*`. Unmatched paths return HTTP 403 before listener dispatch. Groups with authenticator `NONE` pass requests to their listeners, which can still enforce listener-level authentication.

| Path under `/api` | Authentication |
|---|---|
| `/public`, `/public/hello-world` | Anonymous |
| `/basic/hello-world`, `/secret/hello-world` | HTTP Basic via Frank credential alias `hello` |
| `/yaml/hello-world` | HTTP Basic with the private YAML user store |
| `/psk/hello-world` | `X-Customer-Key` checked by Frank against the `partner` alias password. The alias username becomes the authenticated principal. |
| `/container/hello-world` | Tomcat BASIC realm; `web.xml` restricts this prefix and `ROOT.xml` supplies the realm |
| `/ad/hello-world` | Basic credentials validated against Active Directory with a mapped reader group |
| `/bearer-api/hello-world` | Servlet bearer-only resource server, audience and reader role required |
| `/oidc/hello-world` | Browser OAuth2/OIDC login or bearer access; both require the mapped reader role |
| `/sealed/hello-world` | Deny access |
| `/bearer` | Listener validates a signed access token in Authorization |
| `/header-jwt` | Listener validates a signed access token in `X-Customer-Token` |
| `/header`, `/cookie` | Listener validates a cached Frank API principal transmitted as a raw Authorization header or `authenticationToken` cookie |
| `/issue/header`, `/issue/cookie` | POST with a valid issuer JWT to receive a Frank token; all other example operations use GET |

[RoleProtected](configurations/RoleProtected/Configuration.xml) uses listener role checks behind the servlet authenticators. [ListenerModes](configurations/ListenerModes/Configuration.xml) declares the public, JWT and cached-token routes. Business pipelines return JSON. Only the two token-issuance operations create credentials in a pipe, after the JWT check succeeds. Tokens are cached locally with a 300-second TTL.

```sh
base=http://localhost:8180/api
curl -i "$base/public"
curl -i -u hello "$base/basic/hello-world"
curl -i -u yaml-reader "$base/yaml/hello-world"
curl -i -u container-reader "$base/container/hello-world"
curl -i -H "X-Customer-Key: $(cat private/partner.key)" "$base/psk/hello-world"
curl -i "$base/sealed/hello-world"
```

Run `python3 check-local.py` against the running instance to check authentication and credential isolation across routes.

## External identity providers

Use a local `.env` next to `compose.yaml`, or export these variables. The sample IdP host is a placeholder. Populate the URI variables from your actual discovery document.

```dotenv
OIDC_ISSUER=https://sso.example.org/realms/hello
OIDC_AUDIENCE=hello-api
OIDC_JWKS_URI=https://sso.example.org/realms/hello/protocol/openid-connect/certs
OIDC_AUTHORIZATION_URI=https://sso.example.org/realms/hello/protocol/openid-connect/auth
OIDC_TOKEN_URI=https://sso.example.org/realms/hello/protocol/openid-connect/token
OIDC_USERINFO_URI=https://sso.example.org/realms/hello/protocol/openid-connect/userinfo

AD_DOMAIN=example.org
AD_URL=ldaps://dc.example.org:636
AD_BASE_DN=DC=example,DC=org
AD_READER_GROUP=CN=HelloReaders,OU=Groups,DC=example,DC=org
```

Register a confidential OIDC client with S256 PKCE and callback `http://localhost:8180/api/oidc/oauth2/code/custom`. Put the client ID and secret in `private/oidc.properties` as `oidc/username` and `oidc/password`, rerun the initializer and use `docker compose up -d --force-recreate`. Start the login flow at `/api/oidc/oauth2/authorization/custom`. For bearer access, include the API audience and `HelloReader` in `realm_access.roles`. For browser login expose the role in UserInfo. Use a dedicated API audience distinct from the browser client ID. The JVM must trust the IdP and directory certificates.

Configured placeholder endpoints allow local credential routes to start. Successful AD binds and OIDC exchanges require reachable, configured providers.

```sh
curl -i -u 'reader@example.org' "$base/ad/hello-world"
curl -i -H "Authorization: Bearer ${ACCESS_TOKEN}" "$base/bearer-api/hello-world"
curl -i -H "X-Customer-Token: ${ACCESS_TOKEN}" "$base/header-jwt"
token=$(curl -fsS -X POST -H "Authorization: Bearer ${ACCESS_TOKEN}" "$base/issue/header")
curl -i -H "Authorization: $token" "$base/header"
curl -fsS -o /dev/null -c private/cookies -X POST -H "Authorization: Bearer ${ACCESS_TOKEN}" "$base/issue/cookie"
curl -i -b private/cookies "$base/cookie"
```

## Framework behavior

A group belongs to one literal segment below a servlet with one prefix mapping. Each group declares an authenticator. Protected groups require roles. Public groups specify NONE and omit roles. The container authenticator additionally needs a matching application-server constraint. Select GROUPED on the servlet and omit servlet-wide roles when using groups. The existing servlet-wide configuration continues to apply when no groups are configured.

Multiple schemes can operate on different routes concurrently. The OIDC group specifically supports browser login and bearer access on the same route. Arbitrary per-operation AND/OR policies are a separate feature. A custom JWT header carries a signed token; a PSK header carries the configured opaque secret. Both are checked before the business pipeline. Framework code that receives the original servlet request may still read incoming headers.

[Listener modes](https://javadoc.frankframework.org/org/frankframework/http/rest/ApiListener.AuthenticationMethods.html) · [Servlet authenticator types](https://javadoc.frankframework.org/org/frankframework/lifecycle/servlets/AuthenticationType.html)
