fn main() -> Result<(), Box<dyn std::error::Error>> {
    let proto_root = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../proto");

    let proto_path = proto_root.join("inference.proto");

    let out_dir = std::path::PathBuf::from(std::env::var("OUT_DIR").expect("OUT_DIR not set"));

    let result = tonic_build::configure()
        .build_server(true)
        .build_client(true)
        .compile_well_known_types(true)
        .out_dir(&out_dir)
        .compile(
            &[proto_path.to_str().unwrap()],
            &[proto_root.to_str().unwrap()],
        );

    match result {
        Ok(_) => {
            println!("cargo:rerun-if-changed={}", proto_path.to_str().unwrap());
            println!("cargo:rerun-if-changed={}", proto_root.to_str().unwrap());
        }
        Err(e) => {
            eprintln!("cargo:warning=Failed to compile proto: {e}. Using stub.");
            let stub_path = out_dir.join("inference.v1.rs");
            std::fs::write(&stub_path, generate_stub())?;
        }
    }

    Ok(())
}

fn generate_stub() -> String {
    r#"
pub mod inference {
    pub mod v1 {
        #[derive(Clone, Debug, Default)]
        pub struct Tensor;
        #[derive(Clone, Debug, Default)]
        pub struct InferRequest;
        #[derive(Clone, Debug, Default)]
        pub struct InferResponse;
        #[derive(Clone, Debug, Default)]
        pub struct LoadModelRequest;
        #[derive(Clone, Debug, Default)]
        pub struct LoadModelResponse;
        #[derive(Clone, Debug, Default)]
        pub struct UnloadModelRequest;
        #[derive(Clone, Debug, Default)]
        pub struct UnloadModelResponse;
        #[derive(Clone, Debug, Default)]
        pub struct WarmupModelRequest;
        #[derive(Clone, Debug, Default)]
        pub struct WarmupModelResponse;
        #[derive(Clone, Debug, Default)]
        pub struct ModelStatusRequest;
        #[derive(Clone, Debug, Default)]
        pub struct ModelStatusResponse;
        pub mod inference_service_server {
            pub struct InferenceServiceServer<T>(T);
            impl<T> InferenceServiceServer<T> {
                pub fn new(_svc: T) -> Self { Self(std::marker::PhantomData) }
            }
            pub trait InferenceService {}
        }
        pub mod runtime_service_server {
            pub struct RuntimeServiceServer<T>(T);
            impl<T> RuntimeServiceServer<T> {
                pub fn new(_svc: T) -> Self { Self(std::marker::PhantomData) }
            }
            pub trait RuntimeService {}
        }
        pub mod registry_service_server {
            pub struct RegistryServiceServer<T>(T);
            impl<T> RegistryServiceServer<T> {
                pub fn new(_svc: T) -> Self { Self(std::marker::PhantomData) }
            }
            pub trait RegistryService {}
        }
    }
}
"#.to_string()
}
