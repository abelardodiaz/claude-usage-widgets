//! Contrato R5/R6: spec/fixtures/projection.
mod common;

use claude_usage_widgets_lib::model::Window;
use claude_usage_widgets_lib::projection::{project_session, project_weekly};

#[test]
fn fixtures_de_proyeccion() {
    let fixtures = common::load_fixtures("projection");
    assert!(
        fixtures.len() >= 22,
        "faltan fixtures de proyeccion: {}",
        fixtures.len()
    );
    for (name, fx) in fixtures {
        let input = &fx["input"];
        let expected = &fx["expected"];
        let now = common::instant(&input["now"]).unwrap();
        let window = Window {
            percent: input["percent"].as_f64().unwrap(),
            resets_at: common::instant(&input["resets_at"]),
        };
        let samples = common::samples(&input["samples"]);

        let projection = match input["kind"].as_str().unwrap() {
            "session" => project_session(now, &window),
            "weekly" => project_weekly(now, &window, &samples),
            other => panic!("{name}: kind desconocido {other}"),
        };

        common::assert_instant(
            &format!("{name} hits_at"),
            common::instant(&expected["hits_at"]),
            projection.hits_at,
        );
        assert_eq!(
            expected["before_reset"].as_bool(),
            projection.before_reset,
            "{name} before_reset"
        );
        assert_eq!(
            common::basis(&expected["basis"]),
            projection.basis,
            "{name} basis"
        );
    }
}
