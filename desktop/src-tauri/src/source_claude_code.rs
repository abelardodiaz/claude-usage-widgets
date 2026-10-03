//! `ClaudeCodeSource`: GET https://api.anthropic.com/api/oauth/usage con el token de Claude Code.
//! Unico host de red del producto. Sin proxies del entorno, sin redirecciones (el Authorization
//! jamas viaja a otro host), timeout fijo. Los errores nunca incluyen el token ni el cuerpo.
//!
//! LOGS: `ureq_proto` vuelca en `trace!` los bytes crudos de la peticion, incluida la cabecera
//! `Authorization` (el `debug!` de `ureq` si la redacta). Por eso `ureq` y `ureq_proto` nunca a
//! Trace: cualquier logger futuro los fija en `Off`. Lo vigila la prueba `ureq_nunca_a_trace`.

use std::fmt;
use std::io::Read;
use std::path::PathBuf;
use std::time::Duration;

use jiff::Timestamp;
use serde_json::Value;
use ureq::Agent;

use crate::credentials::{CredentialError, read_credential};
use crate::model::{Source, Usage};
use crate::parse::parse_usage;

/// Endpoint no documentado de uso (ver spec 3.1).
pub const USAGE_URL: &str = "https://api.anthropic.com/api/oauth/usage";
/// Unico host permitido para esta fuente (SECURITY.md regla 2).
pub const ALLOWED_HOST: &str = "api.anthropic.com";
/// Tope del cuerpo de la respuesta (igual que `MAX_INPUT` del nucleo Java): 1 MiB. Mas grande
/// es `UnrecognizedFormat` y nunca se lee mas alla del tope.
pub const MAX_BODY: u64 = 1024 * 1024;
const BETA_HEADER: &str = "oauth-2025-04-20";
const USER_AGENT: &str = concat!("claude-usage-widgets/", env!("CARGO_PKG_VERSION"));
const TIMEOUT: Duration = Duration::from_secs(15);
const UTF8_BOM: &[u8] = b"\xEF\xBB\xBF";

/// Errores de la fuente. Solo llevan el codigo HTTP: nunca el token, nunca el cuerpo.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum FetchError {
    #[error(transparent)]
    Credential(#[from] CredentialError),
    #[error("el servicio rechazo la credencial (HTTP {0}); abre Claude Code para renovarla")]
    Rejected(u16),
    #[error("el servicio no esta disponible por ahora (HTTP {0})")]
    Transient(u16),
    #[error("sin conexion con el servicio de uso")]
    Network,
    #[error("respuesta HTTP inesperada ({0})")]
    Unexpected(u16),
    #[error("formato de respuesta no reconocido")]
    UnrecognizedFormat,
}

impl FetchError {
    /// Solo los transitorios activan el backoff.
    pub fn is_transient(&self) -> bool {
        matches!(self, FetchError::Transient(_) | FetchError::Network)
    }
}

/// 200 ok; 401/403 credencial rechazada; 429 y 5xx transitorios; el resto inesperado
/// (incluidas las 3xx: las redirecciones no se siguen).
pub fn classify_status(status: u16) -> Result<(), FetchError> {
    match status {
        200 => Ok(()),
        401 | 403 => Err(FetchError::Rejected(status)),
        429 | 500..=599 => Err(FetchError::Transient(status)),
        other => Err(FetchError::Unexpected(other)),
    }
}

/// Host de una URL `https://host/...` (sin dependencias extra; solo para la prueba de lista blanca).
pub fn url_host(url: &str) -> Option<&str> {
    let rest = url.strip_prefix("https://")?;
    Some(rest.split('/').next().unwrap_or(rest))
}

/// `application/json` o `*/*+json` (sin importar mayusculas ni parametros). Sin cabecera se
/// acepta y decide el cuerpo; cualquier otro tipo (p. ej. una pagina HTML de bloqueo) no.
pub fn content_type_is_json(content_type: Option<&str>) -> bool {
    let Some(value) = content_type else {
        return true;
    };
    let mime = value
        .split(';')
        .next()
        .unwrap_or("")
        .trim()
        .to_ascii_lowercase();
    mime == "application/json" || (mime.contains('/') && mime.ends_with("+json"))
}

/// Lee como maximo `MAX_BODY` bytes; si hay mas, `UnrecognizedFormat` sin seguir leyendo.
/// Un fallo de lectura a mitad del cuerpo cuenta como falta de red.
pub fn read_bounded<R: Read>(body: R) -> Result<Vec<u8>, FetchError> {
    let mut bytes = Vec::new();
    body.take(MAX_BODY + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| FetchError::Network)?;
    if bytes.len() as u64 > MAX_BODY {
        return Err(FetchError::UnrecognizedFormat);
    }
    Ok(bytes)
}

/// UTF-8 (con BOM opcional) -> JSON. Cualquier fallo, incluido el limite de 128 niveles de
/// anidamiento de serde_json, es `UnrecognizedFormat`.
pub fn decode_body(bytes: &[u8]) -> Result<Value, FetchError> {
    let bytes = bytes.strip_prefix(UTF8_BOM).unwrap_or(bytes);
    let text = std::str::from_utf8(bytes).map_err(|_| FetchError::UnrecognizedFormat)?;
    serde_json::from_str(text).map_err(|_| FetchError::UnrecognizedFormat)
}

/// Todo lo que pasa despues de recibir la respuesta, sin red: estado, tipo, tamano, JSON, R1.
/// El cuerpo de una respuesta que no es 200 no se lee.
pub fn interpret_response<R: Read>(
    status: u16,
    content_type: Option<&str>,
    body: R,
) -> Result<Usage, FetchError> {
    classify_status(status)?;
    if !content_type_is_json(content_type) {
        return Err(FetchError::UnrecognizedFormat);
    }
    let bytes = read_bounded(body)?;
    let raw = decode_body(&bytes)?;
    parse_usage(&raw, Source::ClaudeCode).map_err(|_| FetchError::UnrecognizedFormat)
}

pub struct ClaudeCodeSource {
    agent: Agent,
    credentials_path: PathBuf,
}

impl fmt::Debug for ClaudeCodeSource {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("ClaudeCodeSource").finish_non_exhaustive()
    }
}

impl ClaudeCodeSource {
    pub fn new(credentials_path: PathBuf) -> Self {
        // max_redirects(0): una 3xx se devuelve tal cual (ureq no la convierte en error) y
        // classify_status la marca como inesperada.
        let config = Agent::config_builder()
            .timeout_global(Some(TIMEOUT))
            .http_status_as_error(false)
            .proxy(None)
            .max_redirects(0)
            .https_only(true)
            .user_agent(USER_AGENT)
            .build();
        Self {
            agent: Agent::new_with_config(config),
            credentials_path,
        }
    }

    /// Lee el token (en cada consulta), llama al endpoint y normaliza la respuesta.
    pub fn fetch(&self, now: Timestamp) -> Result<Usage, FetchError> {
        let credential = read_credential(&self.credentials_path, now)?;
        let mut response = self
            .agent
            .get(USAGE_URL)
            .header("Authorization", format!("Bearer {}", credential.bearer()))
            .header("anthropic-beta", BETA_HEADER)
            .header("Accept", "application/json")
            .call()
            .map_err(|_| FetchError::Network)?;
        drop(credential);
        let status = response.status().as_u16();
        let content_type = response
            .headers()
            .get("content-type")
            .and_then(|v| v.to_str().ok())
            .map(str::to_owned);
        interpret_response(
            status,
            content_type.as_deref(),
            response.body_mut().as_reader(),
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE_TOKEN: &str = "sk-ant-oat01-MUESTRA-no-debe-salir-0123456789";
    const VALID_BODY: &str = r#"{"five_hour":{"utilization":12.5,"resets_at":null},"seven_day":{"utilization":40,"resets_at":"2026-10-05T10:00:00Z"}}"#;

    #[test]
    fn solo_el_host_permitido() {
        let url = url_host(USAGE_URL);
        assert_eq!(url, Some(ALLOWED_HOST));
        assert!(USAGE_URL.starts_with("https://"));
    }

    #[test]
    fn clasificacion_de_estados() {
        assert_eq!(classify_status(200), Ok(()));
        assert_eq!(classify_status(401), Err(FetchError::Rejected(401)));
        assert_eq!(classify_status(403), Err(FetchError::Rejected(403)));
        assert_eq!(classify_status(429), Err(FetchError::Transient(429)));
        assert_eq!(classify_status(500), Err(FetchError::Transient(500)));
        assert_eq!(classify_status(503), Err(FetchError::Transient(503)));
        assert_eq!(classify_status(404), Err(FetchError::Unexpected(404)));
        assert_eq!(classify_status(302), Err(FetchError::Unexpected(302)));
    }

    #[test]
    fn transitorios() {
        assert!(FetchError::Transient(429).is_transient());
        assert!(FetchError::Network.is_transient());
        assert!(!FetchError::Rejected(401).is_transient());
        assert!(!FetchError::Credential(CredentialError::Expired).is_transient());
        assert!(!FetchError::UnrecognizedFormat.is_transient());
    }

    #[test]
    fn sin_credenciales_no_toca_la_red() {
        let dir = tempfile::tempdir().unwrap();
        let source = ClaudeCodeSource::new(dir.path().join("no-existe.json"));
        let now: Timestamp = "2026-10-02T12:00:00-06:00".parse().unwrap();
        assert_eq!(
            source.fetch(now).unwrap_err(),
            FetchError::Credential(CredentialError::Missing)
        );
    }

    #[test]
    fn credencial_vencida_no_toca_la_red() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join(".credentials.json");
        // expiresAt = 2026-10-01T00:00:00Z, antes de `now`.
        std::fs::write(
            &path,
            format!(r#"{{"claudeAiOauth":{{"accessToken":"{SAMPLE_TOKEN}","expiresAt":1790812800000}}}}"#),
        )
        .unwrap();
        let source = ClaudeCodeSource::new(path);
        let now: Timestamp = "2026-10-02T12:00:00-06:00".parse().unwrap();
        let err = source.fetch(now).unwrap_err();
        assert_eq!(err, FetchError::Credential(CredentialError::Expired));
        assert!(!err.is_transient(), "vencido no activa backoff (D2)");
    }

    #[test]
    fn mensajes_sin_secretos() {
        let errs = [
            FetchError::Credential(CredentialError::Expired),
            FetchError::Rejected(401),
            FetchError::Transient(503),
            FetchError::Network,
            FetchError::Unexpected(404),
            FetchError::UnrecognizedFormat,
        ];
        for e in errs {
            let msg = e.to_string();
            assert!(!msg.contains("Bearer") && !msg.contains("sk-ant"), "{msg}");
        }
    }

    /// Ninguna variante (Display, Debug ni Debug alterno) contiene un token de muestra.
    #[test]
    fn ninguna_variante_muestra_el_token() {
        let errs = [
            FetchError::Credential(CredentialError::Missing),
            FetchError::Credential(CredentialError::Unreadable),
            FetchError::Credential(CredentialError::Expired),
            FetchError::Rejected(401),
            FetchError::Rejected(403),
            FetchError::Transient(429),
            FetchError::Transient(503),
            FetchError::Network,
            FetchError::Unexpected(302),
            FetchError::UnrecognizedFormat,
        ];
        for e in errs {
            for shown in [format!("{e}"), format!("{e:?}"), format!("{e:#?}")] {
                assert!(!shown.contains(SAMPLE_TOKEN), "{shown}");
                assert!(
                    !shown.contains("sk-ant") && !shown.contains("Bearer"),
                    "{shown}"
                );
            }
        }
        let dir = tempfile::tempdir().unwrap();
        let source = ClaudeCodeSource::new(dir.path().join("x.json"));
        let shown = format!("{source:?}");
        assert!(!shown.contains("sk-ant"), "{shown}");
    }

    #[test]
    fn respuesta_valida() {
        let usage =
            interpret_response(200, Some("application/json"), VALID_BODY.as_bytes()).unwrap();
        assert_eq!(usage.source, Source::ClaudeCode);
        assert_eq!(usage.session.percent, 12.5);
        assert_eq!(usage.weekly.percent, 40.0);
        let with_params = interpret_response(
            200,
            Some("Application/JSON; charset=utf-8"),
            VALID_BODY.as_bytes(),
        );
        assert!(with_params.is_ok());
        assert!(interpret_response(200, None, VALID_BODY.as_bytes()).is_ok());
    }

    #[test]
    fn estado_no_200_no_lee_el_cuerpo() {
        // Un lector que falla si se usa: el cuerpo de un error nunca se lee.
        struct Boom;
        impl Read for Boom {
            fn read(&mut self, _: &mut [u8]) -> std::io::Result<usize> {
                panic!("no se debe leer el cuerpo de una respuesta de error");
            }
        }
        assert_eq!(
            interpret_response(401, None, Boom).unwrap_err(),
            FetchError::Rejected(401)
        );
        assert_eq!(
            interpret_response(429, None, Boom).unwrap_err(),
            FetchError::Transient(429)
        );
        assert_eq!(
            interpret_response(302, Some("text/html"), Boom).unwrap_err(),
            FetchError::Unexpected(302)
        );
    }

    #[test]
    fn html_o_tipo_no_json_es_formato_no_reconocido() {
        let html = "<!DOCTYPE html><html><body>Just a moment...</body></html>";
        assert_eq!(
            interpret_response(200, Some("text/html; charset=UTF-8"), html.as_bytes()).unwrap_err(),
            FetchError::UnrecognizedFormat
        );
        assert_eq!(
            interpret_response(200, Some("text/plain"), VALID_BODY.as_bytes()).unwrap_err(),
            FetchError::UnrecognizedFormat
        );
        // Sin Content-Type, el HTML tampoco pasa por JSON.
        assert_eq!(
            interpret_response(200, None, html.as_bytes()).unwrap_err(),
            FetchError::UnrecognizedFormat
        );
        assert!(content_type_is_json(Some("application/problem+json")));
        assert!(!content_type_is_json(Some("application/jsonp")));
    }

    #[test]
    fn tope_de_tamano() {
        let exact = vec![b' '; MAX_BODY as usize];
        assert_eq!(
            read_bounded(exact.as_slice()).unwrap().len(),
            MAX_BODY as usize
        );
        let over = vec![b' '; MAX_BODY as usize + 1];
        assert_eq!(
            read_bounded(over.as_slice()).unwrap_err(),
            FetchError::UnrecognizedFormat
        );
        // Un cuerpo infinito termina en error sin crecer sin limite.
        let endless = std::io::repeat(b'[');
        assert_eq!(
            read_bounded(endless).unwrap_err(),
            FetchError::UnrecognizedFormat
        );
        // JSON valido pero de mas de 1 MiB: rechazado antes de decodificar.
        let mut big = String::from(r#"{"pad":""#);
        big.push_str(&"x".repeat(MAX_BODY as usize));
        big.push_str(r#"","five_hour":{"utilization":1},"seven_day":{"utilization":1}}"#);
        assert_eq!(
            interpret_response(200, Some("application/json"), big.as_bytes()).unwrap_err(),
            FetchError::UnrecognizedFormat
        );
    }

    #[test]
    fn bom_utf8_se_ignora() {
        let mut body = b"\xEF\xBB\xBF".to_vec();
        body.extend_from_slice(VALID_BODY.as_bytes());
        assert!(decode_body(&body).is_ok());
        assert!(interpret_response(200, Some("application/json"), body.as_slice()).is_ok());
    }

    #[test]
    fn json_invalido_es_formato_no_reconocido() {
        assert_eq!(
            decode_body(b"").unwrap_err(),
            FetchError::UnrecognizedFormat
        );
        assert_eq!(
            decode_body(b"{\"a\":").unwrap_err(),
            FetchError::UnrecognizedFormat
        );
        assert_eq!(
            decode_body(b"\xFF\xFE{}").unwrap_err(),
            FetchError::UnrecognizedFormat
        );
        let deep = format!("{}{}", "[".repeat(200), "]".repeat(200));
        assert_eq!(
            decode_body(deep.as_bytes()).unwrap_err(),
            FetchError::UnrecognizedFormat
        );
        // JSON valido pero sin la forma R1.
        assert_eq!(
            interpret_response(200, Some("application/json"), &b"{\"five_hour\":{}}"[..])
                .unwrap_err(),
            FetchError::UnrecognizedFormat
        );
    }

    #[test]
    fn agente_sin_proxy_sin_redirecciones_solo_https() {
        let dir = tempfile::tempdir().unwrap();
        let source = ClaudeCodeSource::new(dir.path().join("x.json"));
        let config = source.agent.config();
        assert_eq!(config.max_redirects(), 0);
        assert!(config.proxy().is_none());
        assert!(config.https_only());
        assert!(!config.http_status_as_error());
    }

    /// ureq_proto vuelca en `trace!` los bytes crudos de la peticion, incluida la cabecera
    /// Authorization. Ningun logger del producto puede habilitar esos targets a Trace.
    #[test]
    fn ureq_nunca_a_trace() {
        for target in ["ureq", "ureq_proto"] {
            assert!(
                !log::log_enabled!(target: target, log::Level::Trace),
                "{target} a Trace"
            );
        }
    }
}
