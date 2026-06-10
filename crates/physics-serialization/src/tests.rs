#[cfg(test)]
mod tests {
    use approx::assert_abs_diff_eq;

    use crate::loader::{from_json_str, to_json_str};
    use crate::scene::{
        BodyConfig, BodyTypeConfig, JointConfig, MaterialConfig, SceneConfig, ShapeConfig,
    };
    use physics_math::Vec2;

    #[test]
    fn test_scene_config_default() {
        let config = SceneConfig::default();
        assert_abs_diff_eq!(config.gravity.y, -9.81);
        assert_abs_diff_eq!(config.time_step, 1.0 / 60.0);
        assert_eq!(config.velocity_iterations, 8);
        assert_eq!(config.position_iterations, 3);
        assert!(config.bodies.is_empty());
        assert!(config.joints.is_empty());
    }

    #[test]
    fn test_material_config_default() {
        let mat = MaterialConfig::default();
        assert_abs_diff_eq!(mat.restitution, 0.2);
        assert_abs_diff_eq!(mat.static_friction, 0.6);
        assert_abs_diff_eq!(mat.dynamic_friction, 0.4);
        assert_abs_diff_eq!(mat.density, 1.0);
    }

    #[test]
    fn test_scene_json_serialization() {
        let mut config = SceneConfig::default();

        config.bodies.push(BodyConfig {
            shape: ShapeConfig::Circle { radius: 1.0 },
            position: Vec2::new(0.0, 5.0),
            angle: 0.0,
            velocity: Some(Vec2::new(1.0, 0.0)),
            angular_velocity: Some(2.0),
            body_type: BodyTypeConfig::Dynamic,
            material: MaterialConfig::default(),
            is_trigger: false,
        });

        config.bodies.push(BodyConfig {
            shape: ShapeConfig::Rectangle {
                width: 2.0,
                height: 1.0,
            },
            position: Vec2::new(0.0, -5.0),
            angle: 0.0,
            velocity: None,
            angular_velocity: None,
            body_type: BodyTypeConfig::Static,
            material: MaterialConfig::default(),
            is_trigger: false,
        });

        config.joints.push(JointConfig::Revolute {
            body_a: 0,
            body_b: 1,
            anchor: Vec2::new(0.0, 0.0),
        });

        let json = to_json_str(&config).unwrap();
        let deserialized = from_json_str(&json).unwrap();

        assert_abs_diff_eq!(deserialized.gravity.y, config.gravity.y);
        assert_eq!(deserialized.bodies.len(), 2);
        assert_eq!(deserialized.joints.len(), 1);

        match &deserialized.bodies[0].shape {
            ShapeConfig::Circle { radius } => assert_abs_diff_eq!(*radius, 1.0),
            _ => panic!("Expected circle shape"),
        }

        assert_abs_diff_eq!(deserialized.bodies[0].position.y, 5.0);
        assert_eq!(deserialized.bodies[1].body_type as u8, BodyTypeConfig::Static as u8);
    }

    #[test]
    fn test_body_type_conversion() {
        let dynamic: physics_core::BodyType = BodyTypeConfig::Dynamic.into();
        let static_body: physics_core::BodyType = BodyTypeConfig::Static.into();
        let kinematic: physics_core::BodyType = BodyTypeConfig::Kinematic.into();

        assert!(matches!(dynamic, physics_core::BodyType::Dynamic));
        assert!(matches!(static_body, physics_core::BodyType::Static));
        assert!(matches!(kinematic, physics_core::BodyType::Kinematic));
    }

    #[test]
    fn test_material_conversion() {
        let config = MaterialConfig {
            restitution: 0.5,
            static_friction: 0.8,
            dynamic_friction: 0.6,
            density: 2.0,
        };

        let mat: physics_core::Material = config.into();

        assert_abs_diff_eq!(mat.restitution, 0.5);
        assert_abs_diff_eq!(mat.static_friction, 0.8);
        assert_abs_diff_eq!(mat.dynamic_friction, 0.6);
        assert_abs_diff_eq!(mat.density, 2.0);
    }

    #[test]
    fn test_polygon_shape_serialization() {
        let mut config = SceneConfig::default();

        config.bodies.push(BodyConfig {
            shape: ShapeConfig::Polygon {
                vertices: vec![
                    Vec2::new(0.0, 0.0),
                    Vec2::new(1.0, 0.0),
                    Vec2::new(0.5, 1.0),
                ],
            },
            position: Vec2::new(0.0, 0.0),
            angle: 0.0,
            velocity: None,
            angular_velocity: None,
            body_type: BodyTypeConfig::Dynamic,
            material: MaterialConfig::default(),
            is_trigger: false,
        });

        let json = to_json_str(&config).unwrap();
        let deserialized = from_json_str(&json).unwrap();

        match &deserialized.bodies[0].shape {
            ShapeConfig::Polygon { vertices } => {
                assert_eq!(vertices.len(), 3);
                assert_abs_diff_eq!(vertices[0].x, 0.0);
                assert_abs_diff_eq!(vertices[2].y, 1.0);
            }
            _ => panic!("Expected polygon shape"),
        }
    }
}
