use ecolor::Color32;
use egui::emath::Rot2;
use egui::{Pos2, Stroke};
use slotmap::SlotMap;

use physics_collision::ContactManifold;
use physics_constraints::{ContactConstraint, DistanceJoint, RevoluteJoint};
use physics_types::{Body, BodyHandle, BodyType, Shape as PhysicsShape};
use physics_math::Vec2;
use physics_particles::Particle;

use crate::colors::*;

type EguiShape = egui::Shape;

pub struct DebugRenderer {
    pub show_aabbs: bool,
    pub show_contact_points: bool,
    pub show_contact_normals: bool,
    pub show_joints: bool,
    pub show_velocities: bool,
    pub scale: f32,
    pub offset: Vec2,
}

impl Default for DebugRenderer {
    fn default() -> Self {
        DebugRenderer {
            show_aabbs: false,
            show_contact_points: true,
            show_contact_normals: true,
            show_joints: true,
            show_velocities: false,
            scale: 50.0,
            offset: Vec2::ZERO,
        }
    }
}

impl DebugRenderer {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn world_to_screen(&self, world_pos: Vec2, canvas_size: Vec2) -> Pos2 {
        let screen_x = (world_pos.x + self.offset.x) * self.scale + canvas_size.x * 0.5;
        let screen_y = (-world_pos.y + self.offset.y) * self.scale + canvas_size.y * 0.5;
        Pos2::new(screen_x, screen_y)
    }

    pub fn draw_body(
        &self,
        body: &Body,
        shapes: &[PhysicsShape],
        canvas_size: Vec2,
    ) -> Vec<EguiShape> {
        let mut output = Vec::new();

        let color = match body.body_type {
            BodyType::Static => STATIC_BODY,
            BodyType::Kinematic => KINEMATIC_BODY,
            BodyType::Dynamic => DYNAMIC_BODY,
        };

        for shape in shapes {
            match shape {
                PhysicsShape::Circle(circle) => {
                    let center = self.world_to_screen(body.transform.position, canvas_size);
                    let radius = circle.radius * self.scale;
                    output.push(EguiShape::circle_stroke(
                        center,
                        radius,
                        Stroke::new(2.0, color),
                    ));

                    let angle = body.transform.rotation.angle();
                    let edge_pos = Pos2::new(
                        center.x + angle.cos() * radius,
                        center.y - angle.sin() * radius,
                    );
                    output.push(EguiShape::line_segment(
                        [center, edge_pos],
                        Stroke::new(2.0, color),
                    ));
                }
                PhysicsShape::Rectangle(rect) => {
                    let half_extents = rect.half_extents * self.scale;
                    let center = self.world_to_screen(body.transform.position, canvas_size);
                    let angle = body.transform.rotation.angle();
                    let rotation = Rot2::from_angle(-angle);

                    let vertices = rect.vertices();
                    let mut screen_vertices = Vec::with_capacity(4);
                    for v in vertices {
                        let local = Vec2::new(v.x * self.scale, -v.y * self.scale);
                        let rotated = rotation * egui::vec2(local.x, local.y);
                        screen_vertices.push(Pos2::new(center.x + rotated.x, center.y + rotated.y));
                    }
                    screen_vertices.push(screen_vertices[0]);

                    output.push(EguiShape::line(screen_vertices, Stroke::new(2.0, color)));
                }
                PhysicsShape::Polygon(polygon) => {
                    let center = self.world_to_screen(body.transform.position, canvas_size);
                    let angle = body.transform.rotation.angle();
                    let rotation = Rot2::from_angle(-angle);

                    let vertices = polygon.vertices();
                    let mut screen_vertices = Vec::with_capacity(vertices.len() + 1);
                    for v in vertices {
                        let local = Vec2::new(v.x * self.scale, -v.y * self.scale);
                        let rotated = rotation * egui::vec2(local.x, local.y);
                        screen_vertices.push(Pos2::new(center.x + rotated.x, center.y + rotated.y));
                    }
                    if !screen_vertices.is_empty() {
                        screen_vertices.push(screen_vertices[0]);
                    }

                    output.push(EguiShape::line(screen_vertices, Stroke::new(2.0, color)));
                }
                PhysicsShape::Segment(segment) => {
                    let start = self.world_to_screen(
                        body.transform.mul_vec(segment.a),
                        canvas_size,
                    );
                    let end = self.world_to_screen(
                        body.transform.mul_vec(segment.b),
                        canvas_size,
                    );
                    output.push(EguiShape::line_segment(
                        [start, end],
                        Stroke::new(2.0, color),
                    ));
                }
                PhysicsShape::HalfSpace(half_space) => {
                    let normal = body.transform.rotation.mul_vec(half_space.normal);
                    let plane_point = normal * half_space.distance;
                    let center = self.world_to_screen(plane_point, canvas_size);
                    
                    let tangent = Vec2::new(-normal.y, normal.x);
                    let line_length = 1000.0;
                    let start = self.world_to_screen(plane_point - tangent * line_length, canvas_size);
                    let end = self.world_to_screen(plane_point + tangent * line_length, canvas_size);
                    
                    output.push(EguiShape::line_segment(
                        [start, end],
                        Stroke::new(3.0, color),
                    ));
                    
                    let normal_start = center;
                    let normal_end = Pos2::new(
                        center.x + normal.x * 30.0,
                        center.y - normal.y * 30.0,
                    );
                    output.push(EguiShape::line_segment(
                        [normal_start, normal_end],
                        Stroke::new(2.0, color),
                    ));
                }
            }
        }

        if self.show_velocities && body.is_dynamic() {
            let start = self.world_to_screen(body.transform.position, canvas_size);
            let vel_end = self.world_to_screen(
                body.transform.position + body.linear_velocity * 0.1,
                canvas_size,
            );
            output.push(EguiShape::line_segment(
                [start, vel_end],
                Stroke::new(1.5, Color32::GREEN),
            ));
        }

        output
    }

    pub fn draw_aabb(
        &self,
        body: &Body,
        shape: &PhysicsShape,
        canvas_size: Vec2,
    ) -> Option<EguiShape> {
        if !self.show_aabbs {
            return None;
        }

        let aabb = shape.compute_aabb(&body.transform);
        let min = self.world_to_screen(Vec2::new(aabb.min.x, aabb.max.y), canvas_size);
        let max = self.world_to_screen(Vec2::new(aabb.max.x, aabb.min.y), canvas_size);

        Some(EguiShape::rect_stroke(
            egui::Rect::from_two_pos(min, max),
            0.0,
            Stroke::new(1.0, aabb_color()),
        ))
    }

    pub fn draw_contacts(
        &self,
        manifolds: &[ContactManifold],
        canvas_size: Vec2,
    ) -> Vec<EguiShape> {
        let mut output = Vec::new();

        if !self.show_contact_points && !self.show_contact_normals {
            return output;
        }

        for manifold in manifolds {
            for i in 0..manifold.point_count {
                let cp = &manifold.points[i];

                if self.show_contact_points {
                    let point = self.world_to_screen(cp.point, canvas_size);
                    output.push(EguiShape::circle_filled(point, 3.0, CONTACT_POINT));
                }

                if self.show_contact_normals {
                    let start = self.world_to_screen(cp.point, canvas_size);
                    let end = self.world_to_screen(cp.point + manifold.normal * 0.3, canvas_size);
                    output.push(EguiShape::line_segment(
                        [start, end],
                        Stroke::new(2.0, CONTACT_NORMAL),
                    ));

                    let arrow_tip = end;
                    let perp = manifold.normal.perp() * 0.05;
                    let arrow_left =
                        self.world_to_screen(cp.point + manifold.normal * 0.25 + perp, canvas_size);
                    let arrow_right =
                        self.world_to_screen(cp.point + manifold.normal * 0.25 - perp, canvas_size);

                    output.push(EguiShape::line_segment(
                        [arrow_tip, arrow_left],
                        Stroke::new(2.0, CONTACT_NORMAL),
                    ));
                    output.push(EguiShape::line_segment(
                        [arrow_tip, arrow_right],
                        Stroke::new(2.0, CONTACT_NORMAL),
                    ));
                }
            }
        }

        output
    }

    pub fn draw_revolute_joint(
        &self,
        joint: &RevoluteJoint,
        bodies: &SlotMap<BodyHandle, Body>,
        canvas_size: Vec2,
    ) -> Option<EguiShape> {
        if !self.show_joints {
            return None;
        }

        let body_a = bodies.get(joint.body_a)?;
        let body_b = bodies.get(joint.body_b)?;

        let world_anchor_a =
            body_a.transform.position + body_a.transform.rotation.mul_vec(joint.local_anchor_a);
        let anchor = self.world_to_screen(world_anchor_a, canvas_size);

        Some(EguiShape::circle_filled(anchor, 5.0, JOINT_COLOR))
    }

    pub fn draw_distance_joint(
        &self,
        joint: &DistanceJoint,
        bodies: &SlotMap<BodyHandle, Body>,
        canvas_size: Vec2,
    ) -> Option<EguiShape> {
        if !self.show_joints {
            return None;
        }

        let body_a = bodies.get(joint.body_a)?;
        let body_b = bodies.get(joint.body_b)?;

        let world_a =
            body_a.transform.position + body_a.transform.rotation.mul_vec(joint.local_anchor_a);
        let world_b =
            body_b.transform.position + body_b.transform.rotation.mul_vec(joint.local_anchor_b);

        let screen_a = self.world_to_screen(world_a, canvas_size);
        let screen_b = self.world_to_screen(world_b, canvas_size);

        Some(EguiShape::line_segment(
            [screen_a, screen_b],
            Stroke::new(2.0, JOINT_COLOR),
        ))
    }

    pub fn draw_particles(
        &self,
        particles: &[Particle],
        canvas_size: Vec2,
        is_fluid: bool,
    ) -> Vec<EguiShape> {
        let mut output = Vec::new();
        let color = if is_fluid { fluid_particle() } else { PARTICLE_COLOR };

        for p in particles {
            let center = self.world_to_screen(p.position, canvas_size);
            let radius = p.radius * self.scale;
            output.push(EguiShape::circle_filled(center, radius, color));
        }

        output
    }

    pub fn draw_all(
        &self,
        bodies: &SlotMap<BodyHandle, Body>,
        body_shapes: &std::collections::HashMap<BodyHandle, Vec<PhysicsShape>>,
        manifolds: &[ContactManifold],
        contacts: &[ContactConstraint],
        revolute_joints: &[RevoluteJoint],
        distance_joints: &[DistanceJoint],
        particles: &[Particle],
        fluid_particles: &[Particle],
        canvas_size: Vec2,
    ) -> Vec<EguiShape> {
        let mut output = Vec::new();

        for (handle, body) in bodies {
            if let Some(shapes) = body_shapes.get(&handle) {
                output.extend(self.draw_body(body, shapes, canvas_size));

                for shape in shapes {
                    if let Some(aabb_shape) = self.draw_aabb(body, shape, canvas_size) {
                        output.push(aabb_shape);
                    }
                }
            }
        }

        output.extend(self.draw_contacts(manifolds, canvas_size));

        for joint in revolute_joints {
            if let Some(shape) = self.draw_revolute_joint(joint, bodies, canvas_size) {
                output.push(shape);
            }
        }

        for joint in distance_joints {
            if let Some(shape) = self.draw_distance_joint(joint, bodies, canvas_size) {
                output.push(shape);
            }
        }

        output.extend(self.draw_particles(particles, canvas_size, false));
        output.extend(self.draw_particles(fluid_particles, canvas_size, true));

        output
    }

    pub fn draw_ui(&mut self, ui: &mut egui::Ui) {
        ui.collapsing("Debug Render Options", |ui| {
            ui.checkbox(&mut self.show_aabbs, "Show AABBs");
            ui.checkbox(&mut self.show_contact_points, "Show Contact Points");
            ui.checkbox(&mut self.show_contact_normals, "Show Contact Normals");
            ui.checkbox(&mut self.show_joints, "Show Joints");
            ui.checkbox(&mut self.show_velocities, "Show Velocities");

            ui.separator();
            ui.label("View:");
            ui.add(egui::Slider::new(&mut self.scale, 1.0..=200.0).text("Scale"));
            ui.add(egui::Slider::new(&mut self.offset.x, -100.0..=100.0).text("Offset X"));
            ui.add(egui::Slider::new(&mut self.offset.y, -100.0..=100.0).text("Offset Y"));
        });
    }
}
