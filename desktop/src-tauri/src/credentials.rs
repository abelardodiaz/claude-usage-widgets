//! Lectura del token OAuth que guarda Claude Code en ~/.claude/.credentials.json.
//! SOLO LECTURA: nunca se refresca (invalidaria el de Claude Code), nunca se copia a otro
//! archivo, nunca aparece en logs, `Debug` ni mensajes de error.

use std::fmt;
use std::path::{Path, PathBuf};

use jiff::Timestamp;
use serde_json::Value;

/// Token OAuth de Claude Code. Solo `bearer()` lo expone, y solo a `source_claude_code`.
pub struct Credential {
    token: String,
    expires_at: Option<Timestamp>,
}

impl Credential {
    pub fn bearer(&self) -> &str {
        &self.token
    }

    pub fn expires_at(&self) -> Option<Timestamp> {
        self.expires_at
    }
}

impl fmt::Debug for Credential {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Credential")
            .field("token", &"***")
            .field("expires_at", &self.expires_at)
            .finish()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub enum CredentialError {
    #[error("no se encontro el archivo de credenciales de Claude Code")]
    Missing,
    #[error("el archivo de credenciales no tiene la forma esperada")]
    Unreadable,
    #[error("el token de Claude Code vencio; abre Claude Code para renovarlo")]
    Expired,
}

/// `~/.claude/.credentials.json` (en Windows, bajo el perfil del usuario).
pub fn default_path() -> Option<PathBuf> {
    dirs::home_dir().map(|home| home.join(".claude").join(".credentials.json"))
}

/// Lee y valida el archivo. Se llama en cada consulta: el token no se guarda en memoria
/// mas alla de la peticion.
pub fn read_credential(path: &Path, now: Timestamp) -> Result<Credential, CredentialError> {
    let text = std::fs::read_to_string(path).map_err(|_| CredentialError::Missing)?;
    parse_credential(&text, now)
}

/// Extrae `claudeAiOauth.accessToken` y `expiresAt` (ms desde epoch). Un `expiresAt`
/// presente y pasado es `Expired`; ausente, se intenta y decide el servidor (D3).
pub fn parse_credential(text: &str, now: Timestamp) -> Result<Credential, CredentialError> {
    let root: Value = serde_json::from_str(text).map_err(|_| CredentialError::Unreadable)?;
    let oauth = root
        .get("claudeAiOauth")
        .and_then(Value::as_object)
        .ok_or(CredentialError::Unreadable)?;
    let token = oauth
        .get("accessToken")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .ok_or(CredentialError::Unreadable)?;
    let expires_at = oauth
        .get("expiresAt")
        .and_then(Value::as_i64)
        .and_then(|ms| Timestamp::from_millisecond(ms).ok());
    if expires_at.is_some_and(|exp| exp <= now) {
        return Err(CredentialError::Expired);
    }
    Ok(Credential {
        token: token.to_string(),
        expires_at,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    const NOW: &str = "2026-10-02T12:00:00-06:00";
    // 2026-10-03T00:00:00Z en milisegundos.
    const TOMORROW_MS: i64 = 1_790_985_600_000;
    // 2026-10-01T00:00:00Z en milisegundos.
    const YESTERDAY_MS: i64 = 1_790_812_800_000;

    fn now() -> Timestamp {
        NOW.parse().unwrap()
    }

    #[test]
    fn token_vigente() {
        let text = format!(
            r#"{{"claudeAiOauth":{{"accessToken":"sk-ant-oat01-prueba","refreshToken":"sk-ant-ort01-prueba","expiresAt":{TOMORROW_MS},"scopes":["user:inference"]}}}}"#
        );
        let c = parse_credential(&text, now()).unwrap();
        assert_eq!(c.bearer(), "sk-ant-oat01-prueba");
        assert_eq!(
            c.expires_at(),
            Some(Timestamp::from_millisecond(TOMORROW_MS).unwrap())
        );
    }

    #[test]
    fn token_vencido() {
        let text = format!(
            r#"{{"claudeAiOauth":{{"accessToken":"sk-ant-oat01-prueba","expiresAt":{YESTERDAY_MS}}}}}"#
        );
        assert_eq!(
            parse_credential(&text, now()).unwrap_err(),
            CredentialError::Expired
        );
    }

    #[test]
    fn sin_expires_at_se_intenta() {
        let text = r#"{"claudeAiOauth":{"accessToken":"sk-ant-oat01-prueba"}}"#;
        let c = parse_credential(text, now()).unwrap();
        assert_eq!(c.expires_at(), None);
    }

    #[test]
    fn formas_invalidas() {
        assert_eq!(
            parse_credential("no json", now()).unwrap_err(),
            CredentialError::Unreadable
        );
        assert_eq!(
            parse_credential(r#"{"otro":1}"#, now()).unwrap_err(),
            CredentialError::Unreadable
        );
        assert_eq!(
            parse_credential(r#"{"claudeAiOauth":{"accessToken":""}}"#, now()).unwrap_err(),
            CredentialError::Unreadable
        );
        assert_eq!(
            parse_credential(r#"{"claudeAiOauth":{"accessToken":7}}"#, now()).unwrap_err(),
            CredentialError::Unreadable
        );
    }

    #[test]
    fn archivo_ausente() {
        let dir = tempfile::tempdir().unwrap();
        let err = read_credential(&dir.path().join("no-existe.json"), now()).unwrap_err();
        assert_eq!(err, CredentialError::Missing);
    }

    #[test]
    fn debug_no_muestra_el_token() {
        let text = r#"{"claudeAiOauth":{"accessToken":"sk-ant-oat01-secreto"}}"#;
        let c = parse_credential(text, now()).unwrap();
        let shown = format!("{c:?}");
        assert!(!shown.contains("secreto"), "Debug filtra el token: {shown}");
        assert!(shown.contains("***"));
    }

    #[test]
    fn mensajes_de_error_sin_datos() {
        for e in [
            CredentialError::Missing,
            CredentialError::Unreadable,
            CredentialError::Expired,
        ] {
            let msg = e.to_string();
            assert!(!msg.is_empty());
            assert!(!msg.contains("sk-ant"));
        }
    }

    /// Ningun `Debug`/`Display` de la credencial ni de sus errores contiene el token, ni
    /// siquiera cuando el error nace de un archivo que si lo trae.
    #[test]
    fn ni_debug_ni_display_contienen_el_token() {
        const SAMPLE: &str = "sk-ant-oat01-MUESTRA-no-debe-salir-0123456789";
        let valid = format!(r#"{{"claudeAiOauth":{{"accessToken":"{SAMPLE}"}}}}"#);
        let c = parse_credential(&valid, now()).unwrap();
        assert!(!format!("{c:?}").contains(SAMPLE));
        assert!(!format!("{c:#?}").contains(SAMPLE));

        let expired = format!(
            r#"{{"claudeAiOauth":{{"accessToken":"{SAMPLE}","expiresAt":{YESTERDAY_MS}}}}}"#
        );
        let broken = format!(r#"{{"claudeAiOauth":{{"accessToken":"{SAMPLE}""#);
        let errors = [
            parse_credential(&expired, now()).unwrap_err(),
            parse_credential(&broken, now()).unwrap_err(),
            CredentialError::Missing,
        ];
        for e in errors {
            for shown in [format!("{e}"), format!("{e:?}"), format!("{e:#?}")] {
                assert!(!shown.contains(SAMPLE), "filtra el token: {shown}");
                assert!(!shown.contains("sk-ant"), "filtra el token: {shown}");
            }
        }
    }
}
