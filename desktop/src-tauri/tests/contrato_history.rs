//! Contrato R2-R4: spec/fixtures/history. `per_day` se compara por union de claves y un 0
//! equivale a ausente (R4).
mod common;

use std::collections::BTreeSet;

use claude_usage_widgets_lib::history::today_stats;
use claude_usage_widgets_lib::timez::zone_from_spec;

#[test]
fn fixtures_de_historial() {
    let fixtures = common::load_fixtures("history");
    assert!(
        fixtures.len() >= 28,
        "faltan fixtures de historial: {}",
        fixtures.len()
    );
    for (name, fx) in fixtures {
        let input = &fx["input"];
        let expected = &fx["expected"];
        let tz = zone_from_spec(input["tz"].as_str().unwrap()).unwrap();
        let now = common::instant(&input["now"]).unwrap();
        let weekly = common::window(&input["weekly"]);
        let samples = common::samples(&input["samples"]);

        let stats = today_stats(now, &weekly, &samples, &tz);

        let exp_days = expected["per_day"].as_object().unwrap();
        let keys: BTreeSet<&str> = exp_days
            .keys()
            .map(String::as_str)
            .chain(stats.per_day.keys().map(String::as_str))
            .collect();
        for k in keys {
            let e = exp_days.get(k).and_then(|v| v.as_f64()).unwrap_or(0.0);
            let a = stats.per_day.get(k).copied().unwrap_or(0.0);
            common::assert_num(&format!("{name} per_day[{k}]"), e, a);
        }
        common::assert_num(
            &format!("{name} today_used"),
            expected["today_used"].as_f64().unwrap(),
            stats.today_used,
        );
        common::assert_num_opt(
            &format!("{name} quota_today"),
            expected["quota_today"].as_f64(),
            stats.quota_today,
        );
        assert_eq!(
            expected["partial"].as_bool().unwrap(),
            stats.partial,
            "{name} partial"
        );
    }
}
