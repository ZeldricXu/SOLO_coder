#[cfg(test)]
mod tests {
    use approx::assert_abs_diff_eq;
    use slotmap::SlotMap;

    use physics_core::{Body, BodyHandle, BodyType, Circle, Material, Shape};
    use physics_math::{Transform, Vec2};

    use crate::constraint::{Constraint, ConstraintSolverData};
    use crate::joints::{DistanceJoint, RevoluteJoint};
    use crate::solver::ConstraintSolver;

    fn create_test_world() -> SlotMap<BodyHandle, Body> {
        SlotMap::with_key()
    }

    fn add_circle_body(
        bodies: &mut SlotMap<BodyHandle, Body>,
        position: Vec2,
        radius: f32,
        body_type: BodyType,
    ) -> BodyHandle {
        let shape = Shape::Circle(Circle::new(radius));
        bodies.insert_with_key(|handle| {
            Body::new(handle, shape, position, 0.0, body_type, Material::DEFAULT)
        })
    }

    #[test]
    fn test_revolute_joint() {
        let mut bodies = create_test_world();

        let body_a = add_circle_body(&mut bodies, Vec2::new(0.0, 0.0), 1.0, BodyType::Dynamic);
        let body_b = add_circle_body(&mut bodies, Vec2::new(2.0, 0.0), 1.0, BodyType::Dynamic);

        let ta = bodies.get(body_a).unwrap().transform;
        let tb = bodies.get(body_b).unwrap().transform;

        let mut joint = RevoluteJoint::new(body_a, body_b, Vec2::new(1.0, 0.0), &ta, &tb);

        let dt = 1.0 / 60.0;
        let mut data = ConstraintSolverData {
            bodies: &mut bodies,
            dt,
            inv_dt: 60.0,
        };

        joint.prepare(&data);
        joint.solve_velocity(&mut data);
        let solved = joint.solve_position(&mut data);

        assert!(solved);
    }

    #[test]
    fn test_distance_joint() {
        let mut bodies = create_test_world();

        let body_a = add_circle_body(&mut bodies, Vec2::new(0.0, 0.0), 1.0, BodyType::Dynamic);
        let body_b = add_circle_body(&mut bodies, Vec2::new(3.0, 0.0), 1.0, BodyType::Dynamic);

        let ta = bodies.get(body_a).unwrap().transform;
        let tb = bodies.get(body_b).unwrap().transform;

        let mut joint =
            DistanceJoint::new(body_a, body_b, Vec2::new(0.0, 0.0), Vec2::new(3.0, 0.0), &ta, &tb);

        let dt = 1.0 / 60.0;
        let mut data = ConstraintSolverData {
            bodies: &mut bodies,
            dt,
            inv_dt: 60.0,
        };

        joint.prepare(&data);
        joint.solve_velocity(&mut data);
        let solved = joint.solve_position(&mut data);

        assert!(solved);
        assert_abs_diff_eq!(joint.length, 3.0);
    }

    #[test]
    fn test_constraint_solver() {
        let mut bodies = create_test_world();

        let body_a = add_circle_body(&mut bodies, Vec2::new(0.0, 0.0), 1.0, BodyType::Dynamic);
        let body_b = add_circle_body(&mut bodies, Vec2::new(2.0, 0.0), 1.0, BodyType::Dynamic);

        let ta = bodies.get(body_a).unwrap().transform;
        let tb = bodies.get(body_b).unwrap().transform;

        let mut joints = vec![RevoluteJoint::new(body_a, body_b, Vec2::new(1.0, 0.0), &ta, &tb)];

        let solver = ConstraintSolver::new(8, 3);
        let dt = 1.0 / 60.0;

        solver.solve(&mut joints, &mut bodies, dt);

        let body_a_pos = bodies.get(body_a).unwrap().transform.position;
        let body_b_pos = bodies.get(body_b).unwrap().transform.position;
        let distance = (body_b_pos - body_a_pos).length();

        assert_abs_diff_eq!(distance, 2.0, epsilon = 0.01);
    }
}
