
use mesh_generator::ElementType;

pub trait ShapeFunction {
    fn evaluate(&self, local_coords: &[f64]) -> Vec<f64>;
    fn derivatives(&self, local_coords: &[f64]) -> Vec<Vec<f64>>;
    fn num_nodes(&self) -> usize;
}

pub struct TriangleShapeFunction;

impl ShapeFunction for TriangleShapeFunction {
    fn evaluate(&self, local_coords: &[f64]) -> Vec<f64> {
        let xi = local_coords[0];
        let eta = local_coords[1];
        vec![
            1.0 - xi - eta,
            xi,
            eta,
        ]
    }

    fn derivatives(&self, _local_coords: &[f64]) -> Vec<Vec<f64>> {
        vec![
            vec![-1.0, -1.0],
            vec![1.0, 0.0],
            vec![0.0, 1.0],
        ]
    }

    fn num_nodes(&self) -> usize {
        3
    }
}

pub struct QuadrilateralShapeFunction;

impl ShapeFunction for QuadrilateralShapeFunction {
    fn evaluate(&self, local_coords: &[f64]) -> Vec<f64> {
        let xi = local_coords[0];
        let eta = local_coords[1];
        vec![
            0.25 * (1.0 - xi) * (1.0 - eta),
            0.25 * (1.0 + xi) * (1.0 - eta),
            0.25 * (1.0 + xi) * (1.0 + eta),
            0.25 * (1.0 - xi) * (1.0 + eta),
        ]
    }

    fn derivatives(&self, local_coords: &[f64]) -> Vec<Vec<f64>> {
        let xi = local_coords[0];
        let eta = local_coords[1];
        vec![
            vec![-0.25 * (1.0 - eta), -0.25 * (1.0 - xi)],
            vec![0.25 * (1.0 - eta), -0.25 * (1.0 + xi)],
            vec![0.25 * (1.0 + eta), 0.25 * (1.0 + xi)],
            vec![-0.25 * (1.0 + eta), 0.25 * (1.0 - xi)],
        ]
    }

    fn num_nodes(&self) -> usize {
        4
    }
}

pub struct TetrahedronShapeFunction;

impl ShapeFunction for TetrahedronShapeFunction {
    fn evaluate(&self, local_coords: &[f64]) -> Vec<f64> {
        let xi = local_coords[0];
        let eta = local_coords[1];
        let zeta = local_coords[2];
        vec![
            1.0 - xi - eta - zeta,
            xi,
            eta,
            zeta,
        ]
    }

    fn derivatives(&self, _local_coords: &[f64]) -> Vec<Vec<f64>> {
        vec![
            vec![-1.0, -1.0, -1.0],
            vec![1.0, 0.0, 0.0],
            vec![0.0, 1.0, 0.0],
            vec![0.0, 0.0, 1.0],
        ]
    }

    fn num_nodes(&self) -> usize {
        4
    }
}

pub struct HexahedronShapeFunction;

impl ShapeFunction for HexahedronShapeFunction {
    fn evaluate(&self, local_coords: &[f64]) -> Vec<f64> {
        let xi = local_coords[0];
        let eta = local_coords[1];
        let zeta = local_coords[2];
        vec![
            0.125 * (1.0 - xi) * (1.0 - eta) * (1.0 - zeta),
            0.125 * (1.0 + xi) * (1.0 - eta) * (1.0 - zeta),
            0.125 * (1.0 + xi) * (1.0 + eta) * (1.0 - zeta),
            0.125 * (1.0 - xi) * (1.0 + eta) * (1.0 - zeta),
            0.125 * (1.0 - xi) * (1.0 - eta) * (1.0 + zeta),
            0.125 * (1.0 + xi) * (1.0 - eta) * (1.0 + zeta),
            0.125 * (1.0 + xi) * (1.0 + eta) * (1.0 + zeta),
            0.125 * (1.0 - xi) * (1.0 + eta) * (1.0 + zeta),
        ]
    }

    fn derivatives(&self, local_coords: &[f64]) -> Vec<Vec<f64>> {
        let xi = local_coords[0];
        let eta = local_coords[1];
        let zeta = local_coords[2];
        vec![
            vec![
                -0.125 * (1.0 - eta) * (1.0 - zeta),
                -0.125 * (1.0 - xi) * (1.0 - zeta),
                -0.125 * (1.0 - xi) * (1.0 - eta),
            ],
            vec![
                0.125 * (1.0 - eta) * (1.0 - zeta),
                -0.125 * (1.0 + xi) * (1.0 - zeta),
                -0.125 * (1.0 + xi) * (1.0 - eta),
            ],
            vec![
                0.125 * (1.0 + eta) * (1.0 - zeta),
                0.125 * (1.0 + xi) * (1.0 - zeta),
                -0.125 * (1.0 + xi) * (1.0 + eta),
            ],
            vec![
                -0.125 * (1.0 + eta) * (1.0 - zeta),
                0.125 * (1.0 - xi) * (1.0 - zeta),
                -0.125 * (1.0 - xi) * (1.0 + eta),
            ],
            vec![
                -0.125 * (1.0 - eta) * (1.0 + zeta),
                -0.125 * (1.0 - xi) * (1.0 + zeta),
                0.125 * (1.0 - xi) * (1.0 - eta),
            ],
            vec![
                0.125 * (1.0 - eta) * (1.0 + zeta),
                -0.125 * (1.0 + xi) * (1.0 + zeta),
                0.125 * (1.0 + xi) * (1.0 - eta),
            ],
            vec![
                0.125 * (1.0 + eta) * (1.0 + zeta),
                0.125 * (1.0 + xi) * (1.0 + zeta),
                0.125 * (1.0 + xi) * (1.0 + eta),
            ],
            vec![
                -0.125 * (1.0 + eta) * (1.0 + zeta),
                0.125 * (1.0 - xi) * (1.0 + zeta),
                0.125 * (1.0 - xi) * (1.0 + eta),
            ],
        ]
    }

    fn num_nodes(&self) -> usize {
        8
    }
}

pub fn get_shape_function(element_type: ElementType) -> Box<dyn ShapeFunction> {
    match element_type {
        ElementType::Triangle => Box::new(TriangleShapeFunction),
        ElementType::Quadrilateral => Box::new(QuadrilateralShapeFunction),
        ElementType::Tetrahedron => Box::new(TetrahedronShapeFunction),
        ElementType::Hexahedron => Box::new(HexahedronShapeFunction),
    }
}
