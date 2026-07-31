import { createServer } from "node:http";
import { generateKeyPairSync, sign } from "node:crypto";

const keyId = "folio-itest";
const { privateKey, publicKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
const publicJwk = publicKey.export({ format: "jwk" });

function json(response, status, body) {
  response.writeHead(status, { "content-type": "application/json" });
  response.end(JSON.stringify(body));
}

function decodeScenario(value, prefix) {
  if (!value?.startsWith(prefix)) {
    throw new Error("invalid scenario token");
  }
  return JSON.parse(Buffer.from(value.slice(prefix.length), "base64url").toString("utf8"));
}

function providerIssuer(subject) {
  const provider = subject.split("-", 1)[0];
  if (provider === "google") return "https://accounts.google.com";
  if (provider === "apple" || provider === "shared") return "https://appleid.apple.com";
  if (provider === "facebook") return "https://www.facebook.com";
  return "https://identity.example";
}

function idToken(request, scenario) {
  const now = Math.floor(Date.now() / 1000);
  const header = Buffer.from(JSON.stringify({ alg: "RS256", kid: keyId, typ: "JWT" })).toString("base64url");
  const claims = {
    iss: `http://${request.headers.host}`,
    sub: `zitadel-${scenario.subject}`,
    aud: "folio-test",
    iat: now - 1,
    exp: now + 300,
    auth_time: now,
    nonce: scenario.nonce,
    amr: scenario.authenticationMethods,
    folio_external_issuer: providerIssuer(scenario.subject),
    folio_external_subject: scenario.subject,
    name: scenario.displayName,
  };
  if (scenario.email !== null) claims.email = scenario.email;
  const payload = Buffer.from(JSON.stringify(claims)).toString("base64url");
  const signature = sign("RSA-SHA256", Buffer.from(`${header}.${payload}`), privateKey).toString("base64url");
  return `${header}.${payload}.${signature}`;
}

createServer((request, response) => {
  if (request.method === "GET" && request.url === "/oauth/v2/keys") {
    json(response, 200, { keys: [{ ...publicJwk, kid: keyId, use: "sig", alg: "RS256" }] });
    return;
  }

  if (request.method === "GET" && request.url === "/oidc/v1/userinfo") {
    try {
      const scenario = decodeScenario(request.headers.authorization?.replace("Bearer ", ""), "scenario-");
      const claims = { sub: `zitadel-${scenario.subject}`, name: scenario.displayName };
      if (scenario.email !== null) claims.email = scenario.email;
      json(response, 200, claims);
    } catch {
      json(response, 401, { error: "invalid_token" });
    }
    return;
  }

  if (request.method === "POST" && request.url === "/oauth/v2/token") {
    let body = "";
    request.on("data", (chunk) => { body += chunk; });
    request.on("end", () => {
      try {
        const parameters = new URLSearchParams(body);
        if (parameters.get("grant_type") !== "authorization_code" || !parameters.get("code_verifier")) {
          json(response, 400, { error: "invalid_request" });
          return;
        }
        const scenario = decodeScenario(parameters.get("code"), "code-");
        if (scenario.subject === "token-failure") {
          json(response, 503, { error: "temporarily_unavailable" });
          return;
        }
        const encodedScenario = Buffer.from(JSON.stringify(scenario)).toString("base64url");
        json(response, 200, {
          access_token: `scenario-${encodedScenario}`,
          token_type: "Bearer",
          expires_in: 300,
          id_token: idToken(request, scenario),
        });
      } catch {
        json(response, 500, { error: "server_error" });
      }
    });
    return;
  }

  json(response, 404, { error: "not_found" });
}).listen(8080, "0.0.0.0");
