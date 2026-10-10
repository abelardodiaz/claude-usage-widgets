//! Contrato R7: spec/fixtures/colors. `input.bar` dice que se pinta; los colores viajan como
//! "green" | "amber" | "red" | "gray" (lo que serializa `Color`) y el estado del relleno de hoy
//! como "unknown" | "exhausted" | "ok" (lo que serializa `TodayState`).
mod common;

use claude_usage_widgets_lib::colors::{
    Color, TodayState, bar_color, pace_mark, today_color, today_fill,
};
use serde_json::Value;

fn color_from(v: &Value) -> Color {
    match v.as_str() {
        Some("green") => Color::Green,
        Some("amber") => Color::Amber,
        Some("red") => Color::Red,
        Some("gray") => Color::Gray,
        other => panic!("color desconocido {other:?}"),
    }
}

#[test]
fn fixtures_de_colores() {
    let fixtures = common::load_fixtures("colors");
    assert!(
        fixtures.len() >= 26,
        "faltan fixtures de colores: {}",
        fixtures.len()
    );
    for (name, fx) in fixtures {
        let input = &fx["input"];
        let expected = &fx["expected"];
        match input["bar"].as_str().unwrap() {
            "session" | "weekly" | "scoped" => {
                let actual = bar_color(input["percent"].as_f64().unwrap());
                assert_eq!(color_from(&expected["color"]), actual, "{name}");
            }
            "today" => {
                let actual = today_color(
                    input["today_used"].as_f64().unwrap(),
                    input["quota_today"].as_f64(),
                );
                assert_eq!(color_from(&expected["color"]), actual, "{name}");
            }
            "today_fill" => {
                let actual = today_fill(
                    input["today_used"].as_f64().unwrap(),
                    input["quota_today"].as_f64(),
                );
                assert_eq!(
                    expected["state"],
                    serde_json::to_value(actual.state).unwrap(),
                    "{name} state"
                );
                common::assert_num_opt(
                    &format!("{name} fraction"),
                    expected["fraction"].as_f64(),
                    actual.fraction,
                );
            }
            "pace_mark" => {
                let now = common::instant(&input["now"]).unwrap();
                let actual = pace_mark(now, common::instant(&input["resets_at"]));
                common::assert_num_opt(&format!("{name} mark"), expected["mark"].as_f64(), actual);
            }
            other => panic!("{name}: bar desconocida {other}"),
        }
    }
}

#[test]
fn los_colores_serializan_en_minusculas() {
    assert_eq!(serde_json::to_string(&Color::Green).unwrap(), "\"green\"");
    assert_eq!(serde_json::to_string(&Color::Amber).unwrap(), "\"amber\"");
    assert_eq!(serde_json::to_string(&Color::Red).unwrap(), "\"red\"");
    assert_eq!(serde_json::to_string(&Color::Gray).unwrap(), "\"gray\"");
}

#[test]
fn los_estados_de_hoy_serializan_en_minusculas() {
    assert_eq!(
        serde_json::to_string(&TodayState::Unknown).unwrap(),
        "\"unknown\""
    );
    assert_eq!(
        serde_json::to_string(&TodayState::Exhausted).unwrap(),
        "\"exhausted\""
    );
    assert_eq!(serde_json::to_string(&TodayState::Ok).unwrap(), "\"ok\"");
}
