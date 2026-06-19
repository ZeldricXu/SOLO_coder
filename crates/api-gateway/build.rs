fn main() -> Result<(), Box<dyn std::error::Error>> {
    let proto_root = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../proto");

    let proto_path = proto_root.join("inference.proto");

    tonic_build::configure()
        .build_server(true)
        .build_client(true)
        .compile_well_known_types(true)
        .out_dir(std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("src"))
        .compile(
            &[proto_path.to_str().unwrap()],
            &[proto_root.to_str().unwrap()],
        )?;

    println!("cargo:rerun-if-changed={}", proto_path.to_str().unwrap());

    Ok(())
}
