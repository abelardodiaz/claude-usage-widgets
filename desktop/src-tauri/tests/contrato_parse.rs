//! Contrato R1/R1b: cada fixture de spec/fixtures/parse produce exactamente el `expected`.
mod common;

use claude_usage_widgets_lib::model::{Source, Usage};
use claude_usage_widgets_lib::parse::{ParseError, parse_usage};
use serde_json::Value;

fn check_usage(name: &str, expected: &Value, usage: &Usage) {
    for (field, exp, act) in [
        ("session", &expected["session"], &usage.session),
        ("weekly", &expected["weekly"], &usage.weekly),
    ] {
        common::assert_num(
            &format!("{name} {field}.percent"),
            exp["percent"].as_f64().unwrap(),
            act.percent,
        );
        common::assert_instant(
            &format!("{name} {field}.resets_at"),
            common::instant(&exp["resets_at"]),
            act.resets_at,
        );
    }

    let scoped = expected["scoped"].as_array().unwrap();
    assert_eq!(scoped.len(), usage.scoped.len(), "{name} scoped.len");
    for (i, (exp, act)) in scoped.iter().zip(&usage.scoped).enumerate() {
        assert_eq!(
            exp["label"].as_str().unwrap(),
            act.label,
            "{name} scoped[{i}].label"
        );
        common::assert_num(
            &format!("{name} scoped[{i}].percent"),
            exp["percent"].as_f64().unwrap(),
            act.percent,
        );
        common::assert_instant(
            &format!("{name} scoped[{i}].resets_at"),
            common::instant(&exp["resets_at"]),
            act.resets_at,
        );
    }

    let rows = expected["breakdown"].as_array().unwrap();
    assert_eq!(rows.len(), usage.breakdown.len(), "{name} breakdown.len");
    for (i, (exp, act)) in rows.iter().zip(&usage.breakdown).enumerate() {
        assert_eq!(
            exp["key"].as_str().unwrap(),
            act.key,
            "{name} breakdown[{i}].key"
        );
        assert_eq!(
            exp["label"].as_str().unwrap(),
            act.label,
            "{name} breakdown[{i}].label"
        );
        common::assert_num(
            &format!("{name} breakdown[{i}].percent"),
            exp["percent"].as_f64().unwrap(),
            act.percent,
        );
    }
}

#[test]
fn fixtures_de_parseo() {
    let fixtures = common::load_fixtures("parse");
    assert!(
        fixtures.len() >= 6,
        "faltan fixtures de parseo: {}",
        fixtures.len()
    );
    for (name, fx) in fixtures {
        let source = match fx["source"].as_str() {
            Some("claude_ai") => Source::ClaudeAi,
            _ => Source::ClaudeCode,
        };
        let expected = &fx["expected"];
        let result = parse_usage(&fx["input"], source);
        if expected.get("error").is_some() {
            assert_eq!(
                result,
                Err(ParseError::UnrecognizedFormat),
                "{name}: debia fallar"
            );
            continue;
        }
        let usage = result.unwrap_or_else(|e| panic!("{name}: {e}"));
        assert_eq!(usage.source, source, "{name} source");
        check_usage(&name, expected, &usage);
    }
}
