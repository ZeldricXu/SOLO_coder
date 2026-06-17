provider "aws" {
  region = "us-east-1"
}

provider "azure" {
  region = "eastus"
}

provider "gcp" {
  region = "us-central1"
  project_id = "my-gcp-project"
}

variable "environment" {
  type    = string
  default = "dev"
}

variable "owner" {
  type    = string
  default = "devops-team"
}

resource "aws_compute" "web_server" {
  name   = "dev-compute-web-server"
  type   = "compute"
  region = "us-east-1"

  properties = {
    instance_type = "t3.large"
    ami           = "ami-1234567890abcdef0"
    vpc_id        = "vpc-12345678"
    subnet_id     = "subnet-12345678"
  }

  tags = {
    Environment = "dev"
    Owner       = "devops-team"
    Project     = "multicloud-demo"
  }

  depends_on = ["aws_security_group_web"]
}

resource "aws_security_group" "web" {
  name   = "dev-security-web-sg"
  type   = "security"
  region = "us-east-1"

  properties = {
    vpc_id = "vpc-12345678"
    ingress_rules = [
      {
        from_port   = 80
        to_port     = 80
        protocol    = "tcp"
        cidr_blocks = ["0.0.0.0/0"]
      },
      {
        from_port   = 443
        to_port     = 443
        protocol    = "tcp"
        cidr_blocks = ["0.0.0.0/0"]
      }
    ]
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
    bucket_policy  = "private"
  }

  tags = {
    Environment = "dev"
    Owner       = "devops-team"
    DataClass   = "sensitive"
  }
}

resource "azure_storage" "backup_account" {
  name   = "dev-storage-backup-account"
  type   = "storage"
  region = "eastus"

  properties = {
    encryption     = true
    public_access  = false
    account_tier   = "Standard"
    replication    = "GRS"
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
    disk_size_gb     = 100
    auto_upgrade     = true
    auto_repair      = true
    network_policy   = true
  }

  tags = {
    Environment = "dev"
    Owner       = "devops-team"
    Tier        = "production"
  }
}

resource "aws_database" "primary_db" {
  name   = "dev-database-primary-db"
  type   = "database"
  region = "us-east-1"

  properties = {
    engine          = "postgres"
    engine_version  = "15.3"
    instance_class  = "db.t3.large"
    storage_gb      = 100
    backup_enabled  = true
    password        = "MyStr0ng!Passw0rd#2024"
    multi_az        = true
  }

  tags = {
    Environment = "dev"
    Owner       = "devops-team"
    DataClass   = "confidential"
  }

  depends_on = ["aws_security_group_web"]
}

output "web_server_ip" {
  value = "${aws_compute.web_server.properties.public_ip}"
}

output "bucket_name" {
  value = "${aws_storage.app_bucket.name}"
}

output "cluster_endpoint" {
  value = "${gcp_kubernetes.main_cluster.properties.endpoint}"
}
