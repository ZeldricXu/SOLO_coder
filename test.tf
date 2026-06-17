resource "aws_compute" "web_server" {
  name   = "dev-compute-web-server"
  type   = "compute"
  region = "us-east-1"

  properties = {
    instance_type = "t3.large"
    ami           = "ami-1234567890abcdef0"
  }

  tags = {
    Environment = "dev"
    Owner       = "devops-team"
  }
}

resource "aws_storage" "app_bucket" {
  name   = "dev-storage-app-bucket"
  type   = "storage"
  region = "us-east-1"

  properties = {
    encryption     = true
    public_access  = false
    versioning     = true
  }

  tags = {
    Environment = "dev"
    Owner       = "devops-team"
  }
}

resource "gcp_kubernetes" "main_cluster" {
  name   = "dev-kubernetes-main-cluster"
  type   = "kubernetes"
  region = "us-central1"

  properties = {
    node_count       = 3
    machine_type     = "e2-standard-4"
    auto_upgrade     = true
  }

  tags = {
    Environment = "dev"
    Owner       = "devops-team"
  }

  depends_on = ["web_server"]
}
