use physics_types::{Body, BodyHandle};
use physics_math::{Rot2, Transform};
use crate::contact::{Collide, ContactManifold};

const CCD_MAX_ITERATIONS: usize = 20;
const CCD_TOLERANCE: f32 = 0.001;

pub struct CCDResult {
    pub toi: f32,
    pub target_handle: BodyHandle,
    pub manifold: ContactManifold,
}

pub fn ccd_step(
    body_handle: BodyHandle,
    dt: f32,
    candidate_handles: &[BodyHandle],
    bodies: &slotmap::SlotMap<BodyHandle, Body>,
) -> Option<CCDResult> {
    let body = bodies.get(body_handle)?;
    if !body.is_dynamic() {
        return None;
    }

    let start_transform = body.transform;
    let end_position = start_transform.position + body.linear_velocity * dt;
    let end_angle = start_transform.rotation.angle() + body.angular_velocity * dt;
    let end_transform = Transform::new(end_position, Rot2::new(end_angle));

    let mut t_lo = 0.0;
    let mut t_hi = 1.0;
    let mut found_collision = false;
    let mut best_target: Option<BodyHandle> = None;
    let mut best_manifold: Option<ContactManifold> = None;

    // 先检查终点是否碰撞
    let end_t = interpolate_transform(&start_transform, &end_transform, t_hi);
    for &target_handle in candidate_handles {
        if target_handle == body_handle {
            continue;
        }
        let target = match bodies.get(target_handle) {
            Some(b) => b,
            None => continue,
        };
        if let Some(manifold) =
            body.shape
                .collide(&end_t, &target.shape, &target.transform)
        {
            found_collision = true;
            best_target = Some(target_handle);
            best_manifold = Some(manifold);
            break;
        }
    }

    if !found_collision {
        return None;
    }

    // 检查起点是否碰撞（如果起点就碰撞则直接返回）
    let start_t = interpolate_transform(&start_transform, &end_transform, t_lo);
    for &target_handle in candidate_handles {
        if target_handle == body_handle {
            continue;
        }
        let target = match bodies.get(target_handle) {
            Some(b) => b,
            None => continue,
        };
        if let Some(manifold) =
            body.shape
                .collide(&start_t, &target.shape, &target.transform)
        {
            return Some(CCDResult {
                toi: 0.0,
                target_handle,
                manifold,
            });
        }
    }

    // 二分搜索找到精确的碰撞时刻
    for _ in 0..CCD_MAX_ITERATIONS {
        let t_mid = (t_lo + t_hi) * 0.5;
        let mid_transform = interpolate_transform(&start_transform, &end_transform, t_mid);

        let mut collision_at_mid = false;
        for &target_handle in candidate_handles {
            if target_handle == body_handle {
                continue;
            }
            let target = match bodies.get(target_handle) {
                Some(b) => b,
                None => continue,
            };
            if let Some(manifold) =
                body.shape
                    .collide(&mid_transform, &target.shape, &target.transform)
            {
                collision_at_mid = true;
                best_target = Some(target_handle);
                best_manifold = Some(manifold);
                break;
            }
        }

        if collision_at_mid {
            t_hi = t_mid;
        } else {
            t_lo = t_mid;
        }

        if t_hi - t_lo < CCD_TOLERANCE {
            break;
        }
    }

    let target_handle = best_target?;
    let manifold = best_manifold?;

    Some(CCDResult {
        toi: t_hi,
        target_handle,
        manifold,
    })
}

fn interpolate_transform(a: &Transform, b: &Transform, t: f32) -> Transform {
    let position = a.position + (b.position - a.position) * t;
    let angle = a.rotation.angle() + (b.rotation.angle() - a.rotation.angle()) * t;
    Transform::new(position, Rot2::new(angle))
}
