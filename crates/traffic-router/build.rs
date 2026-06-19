fn main() -> Result<(), Box<dyn std::error::Error>> {
    let proto_root = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../proto");

    let proto_path = proto_root.join("inference.proto");
    let out_dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("src");
    let out_file = out_dir.join("inference.v1.rs");

    let proto_exists = proto_path.exists();
    let out_exists = out_file.exists();
    let has_protoc = std::process::Command::new("protoc")
        .arg("--version")
        .output()
        .is_ok();

    if has_protoc && proto_exists {
        match tonic_build::configure()
            .build_server(false)
            .build_client(true)
            .compile_well_known_types(true)
            .out_dir(&out_dir)
            .compile(
                &[proto_path.to_str().unwrap()],
                &[proto_root.to_str().unwrap()],
            ) {
            Ok(()) => {
                if proto_exists {
                    println!("cargo:rerun-if-changed={}", proto_path.to_str().unwrap());
                }
                return Ok(());
            }
            Err(e) => {
                eprintln!("cargo:warning=tonic_build failed ({}), falling back to pre-generated inference.v1.rs", e);
            }
        }
    } else if !out_exists {
        eprintln!("cargo:warning=protoc not available and no pre-generated inference.v1.rs found");
        eprintln!("cargo:warning=Please install protobuf: brew install protobuf");
    } else {
        eprintln!("cargo:warning=Using pre-generated inference.v1.rs (install protoc for auto-generation)");
    }

    if proto_exists {
        println!("cargo:rerun-if-changed={}", proto_path.to_str().unwrap());
    }

    Ok(())
}
