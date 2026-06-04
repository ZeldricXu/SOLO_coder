use std::fs::File;
use std::io::{self, BufRead, BufReader};
use std::path::Path;
use thiserror::Error;
use nalgebra::Point3;
use crate::element::{Element, ElementType};

#[derive(Error, Debug)]
pub enum ParserError {
    #[error("IO error: {0}")]
    Io(#[from] io::Error),
    #[error("Parse error: {0}")]
    Parse(String),
    #[error("Unsupported element type: {0}")]
    UnsupportedElementType(i32),
}

pub struct GmshParser;

impl GmshParser {
    pub fn parse<P: AsRef<Path>>(path: P) -> Result<(Vec<Point3<f64>>, Vec<Element>), ParserError> {
        let file = File::open(path)?;
        let reader = BufReader::new(file);
        let lines: Vec<String> = reader.lines().collect::<Result<_, _>>()?;

        let mut nodes: Vec<Point3<f64>> = Vec::new();
        let mut elements: Vec<Element> = Vec::new();

        let mut i = 0;
        while i < lines.len() {
            let line = &lines[i];
            match line.trim() {
                "$Nodes" => {
                    i += 1;
                    let num_nodes: usize = lines[i].trim().parse()
                        .map_err(|_| ParserError::Parse("Failed to parse number of nodes".into()))?;
                    i += 1;
                    
                    nodes.reserve(num_nodes);
                    for _ in 0..num_nodes {
                        let parts: Vec<&str> = lines[i].split_whitespace().collect();
                        if parts.len() >= 4 {
                            let x: f64 = parts[1].parse().unwrap_or(0.0);
                            let y: f64 = parts[2].parse().unwrap_or(0.0);
                            let z: f64 = parts[3].parse().unwrap_or(0.0);
                            nodes.push(Point3::new(x, y, z));
                        }
                        i += 1;
                    }
                }
                "$Elements" => {
                    i += 1;
                    let num_elems: usize = lines[i].trim().parse()
                        .map_err(|_| ParserError::Parse("Failed to parse number of elements".into()))?;
                    i += 1;
                    
                    elements.reserve(num_elems);
                    for _ in 0..num_elems {
                        let parts: Vec<&str> = lines[i].split_whitespace().collect();
                        if parts.len() >= 3 {
                            let elem_type: i32 = parts[1].parse().unwrap_or(0);
                            let num_tags: usize = parts[2].parse().unwrap_or(0);
                            
                            let tag = if num_tags > 0 {
                                parts[3].parse().unwrap_or(0)
                            } else {
                                0
                            };
                            
                            let node_start = 3 + num_tags;
                            let node_indices: Vec<usize> = parts[node_start..]
                                .iter()
                                .map(|s| s.parse::<usize>().unwrap_or(0) - 1)
                                .collect();
                            
                            match Self::gmsh_type_to_element(elem_type) {
                                Some(elem_type_enum) => {
                                    elements.push(Element::new(elem_type_enum, node_indices, tag));
                                }
                                None => {
                                    return Err(ParserError::UnsupportedElementType(elem_type));
                                }
                            }
                        }
                        i += 1;
                    }
                }
                _ => {
                    i += 1;
                }
            }
        }

        Ok((nodes, elements))
    }

    fn gmsh_type_to_element(gmsh_type: i32) -> Option<ElementType> {
        match gmsh_type {
            2 => Some(ElementType::Triangle),
            3 => Some(ElementType::Quadrilateral),
            4 => Some(ElementType::Tetrahedron),
            5 => Some(ElementType::Hexahedron),
            _ => None,
        }
    }
}
