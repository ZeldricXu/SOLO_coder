use anyhow::{Result, anyhow};
use crate::models::{PackageType, SbomFormat, SbomPackage, SbomDocument};

pub struct SbomParser;

impl SbomParser {
    pub fn parse_spdx(json: &str) -> Result<SbomDocument> {
        let value: serde_json::Value = serde_json::from_str(json)?;
        let name = value["name"].as_str().unwrap_or("unknown").to_string();
        let version = value["SPDXID"].as_str().unwrap_or("1.0").to_string();
        let packages = value["packages"]
            .as_array()
            .map(|arr| {
                arr.iter().filter_map(|pkg| Self::parse_spdx_package(pkg)).collect::<Vec<_>>()
            })
            .unwrap_or_default();
        Ok(SbomDocument {
            format: SbomFormat::Spdx,
            name,
            version,
            packages,
        })
    }

    pub fn parse_cyclonedx(json: &str) -> Result<SbomDocument> {
        let value: serde_json::Value = serde_json::from_str(json)?;
        let name = value["metadata"]["component"]["name"].as_str().unwrap_or("unknown").to_string();
        let version = value["version"].as_str().unwrap_or("1").to_string();
        let packages = value["components"]
            .as_array()
            .map(|arr| {
                arr.iter().filter_map(|comp| Self::parse_cyclonedx_component(comp)).collect::<Vec<_>>()
            })
            .unwrap_or_default();
        Ok(SbomDocument {
            format: SbomFormat::CycloneDX,
            name,
            version,
            packages,
        })
    }

    pub fn parse(input: &str) -> Result<SbomDocument> {
        let trimmed = input.trim();
        let value: serde_json::Value = serde_json::from_str(trimmed)
            .map_err(|e| anyhow!("Invalid JSON input: {}", e))?;
        if value.get("SPDXID").is_some() || value.get("spdxVersion").is_some() {
            Self::parse_spdx(trimmed)
        } else if value.get("bomFormat").is_some() || value.get("components").is_some() {
            Self::parse_cyclonedx(trimmed)
        } else {
            Err(anyhow!("Unable to detect SBOM format: neither SPDX nor CycloneDX"))
        }
    }

    fn parse_spdx_package(pkg: &serde_json::Value) -> Option<SbomPackage> {
        let name = pkg["name"].as_str()?.to_string();
        let version = pkg["versionInfo"].as_str().unwrap_or("0.0.0").to_string();
        let purl = pkg["externalRefs"]
            .as_array()
            .and_then(|refs| {
                refs.iter().find_map(|r| {
                    if r["referenceCategory"].as_str() == Some("PACKAGE_MANAGER") {
                        r["referenceLocator"].as_str().map(|s| s.to_string())
                    } else {
                        None
                    }
                })
            });
        let package_type = purl.as_ref().map(|p| Self::purl_to_package_type(p)).unwrap_or(PackageType::Npm);
        let license = pkg["licenseConcluded"].as_str().map(|s| s.to_string());
        Some(SbomPackage {
            name,
            version,
            package_type,
            purl,
            license,
        })
    }

    fn parse_cyclonedx_component(comp: &serde_json::Value) -> Option<SbomPackage> {
        let name = comp["name"].as_str()?.to_string();
        let version = comp["version"].as_str().unwrap_or("0.0.0").to_string();
        let purl = comp["purl"].as_str().map(|s| s.to_string());
        let package_type = comp["type"]
            .as_str()
            .map(|t| Self::cyclonedx_type_to_package_type(t))
            .unwrap_or_else(|| purl.as_ref().map(|p| Self::purl_to_package_type(p)).unwrap_or(PackageType::Npm));
        let license = comp["licenses"]
            .as_array()
            .and_then(|arr| arr.first())
            .and_then(|l| l["license"]["id"].as_str().map(|s| s.to_string()));
        Some(SbomPackage {
            name,
            version,
            package_type,
            purl,
            license,
        })
    }

    fn purl_to_package_type(purl: &str) -> PackageType {
        if purl.starts_with("pkg:npm/") {
            PackageType::Npm
        } else if purl.starts_with("pkg:pypi/") {
            PackageType::PyPI
        } else if purl.starts_with("pkg:cargo/") {
            PackageType::Cargo
        } else if purl.starts_with("pkg:maven/") {
            PackageType::Maven
        } else if purl.starts_with("pkg:golang/") {
            PackageType::Go
        } else if purl.starts_with("pkg:nuget/") {
            PackageType::NuGet
        } else {
            PackageType::Npm
        }
    }

    fn cyclonedx_type_to_package_type(t: &str) -> PackageType {
        match t {
            "npm" => PackageType::Npm,
            "pypi" => PackageType::PyPI,
            "cargo" => PackageType::Cargo,
            "maven" => PackageType::Maven,
            "golang" => PackageType::Go,
            "nuget" => PackageType::NuGet,
            _ => PackageType::Npm,
        }
    }
}
