CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(100) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    phone VARCHAR(20),
    avatar_url TEXT,
    department VARCHAR(100),
    position VARCHAR(100),
    role VARCHAR(20) NOT NULL DEFAULT 'employee',
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS onboarding_templates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(200) NOT NULL,
    description TEXT,
    position_type VARCHAR(100) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS template_task_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id UUID NOT NULL REFERENCES onboarding_templates(id) ON DELETE CASCADE,
    title VARCHAR(300) NOT NULL,
    description TEXT,
    category VARCHAR(50) NOT NULL,
    assignee_type VARCHAR(50) NOT NULL,
    assignee_role VARCHAR(100),
    sort_order INT NOT NULL DEFAULT 0,
    deadline_offset_hours INT NOT NULL DEFAULT 72,
    is_required BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS employees (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id),
    name VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL,
    phone VARCHAR(20),
    department VARCHAR(100),
    position VARCHAR(100),
    position_type VARCHAR(100),
    hire_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    emergency_contact JSONB,
    bank_info JSONB,
    personal_info JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS onboardings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id UUID NOT NULL REFERENCES employees(id),
    template_id UUID NOT NULL REFERENCES onboarding_templates(id),
    hr_id UUID NOT NULL REFERENCES users(id),
    mentor_id UUID REFERENCES users(id),
    leader_id UUID REFERENCES users(id),
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    start_date DATE NOT NULL,
    progress INT NOT NULL DEFAULT 0,
    portal_token VARCHAR(255) UNIQUE,
    portal_expires_at TIMESTAMPTZ,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS onboarding_tasks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    onboarding_id UUID NOT NULL REFERENCES onboardings(id) ON DELETE CASCADE,
    template_item_id UUID REFERENCES template_task_items(id),
    title VARCHAR(300) NOT NULL,
    description TEXT,
    category VARCHAR(50) NOT NULL,
    assignee_type VARCHAR(50) NOT NULL,
    assignee_id UUID REFERENCES users(id),
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    priority VARCHAR(20) NOT NULL DEFAULT 'normal',
    deadline TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    completed_by UUID REFERENCES users(id),
    escalation_count INT NOT NULL DEFAULT 0,
    sort_order INT NOT NULL DEFAULT 0,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    onboarding_id UUID REFERENCES onboardings(id) ON DELETE CASCADE,
    employee_id UUID NOT NULL REFERENCES employees(id),
    title VARCHAR(300) NOT NULL,
    doc_type VARCHAR(50) NOT NULL,
    category VARCHAR(50) NOT NULL,
    file_url TEXT,
    file_key VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    esign_flow_id VARCHAR(255),
    esign_provider VARCHAR(50),
    esign_status VARCHAR(20) NOT NULL DEFAULT 'none',
    signed_at TIMESTAMPTZ,
    content_hash VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS document_signatures (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    signer_name VARCHAR(100) NOT NULL,
    signer_email VARCHAR(255) NOT NULL,
    signer_type VARCHAR(50) NOT NULL,
    signer_id UUID REFERENCES users(id),
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    signed_at TIMESTAMPTZ,
    sign_url TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS it_automation_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    onboarding_id UUID NOT NULL REFERENCES onboardings(id) ON DELETE CASCADE,
    task_id UUID REFERENCES onboarding_tasks(id),
    job_type VARCHAR(50) NOT NULL,
    target_system VARCHAR(50) NOT NULL,
    params JSONB NOT NULL DEFAULT '{}',
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    result JSONB,
    retry_count INT NOT NULL DEFAULT 0,
    max_retries INT NOT NULL DEFAULT 3,
    error_message TEXT,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS portal_timeline_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    onboarding_id UUID NOT NULL REFERENCES onboardings(id) ON DELETE CASCADE,
    title VARCHAR(300) NOT NULL,
    description TEXT,
    phase VARCHAR(50) NOT NULL,
    day_offset INT NOT NULL DEFAULT 0,
    time_slot VARCHAR(50),
    item_type VARCHAR(50) NOT NULL DEFAULT 'info',
    is_completed BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    title VARCHAR(300) NOT NULL,
    content TEXT,
    type VARCHAR(50) NOT NULL,
    reference_type VARCHAR(50),
    reference_id UUID,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS survey_responses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    onboarding_id UUID NOT NULL REFERENCES onboardings(id) ON DELETE CASCADE,
    employee_id UUID NOT NULL REFERENCES employees(id),
    overall_score INT,
    questions JSONB NOT NULL DEFAULT '[]',
    comments TEXT,
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS self_service_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id UUID NOT NULL REFERENCES employees(id),
    request_type VARCHAR(50) NOT NULL,
    title VARCHAR(300) NOT NULL,
    description TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    attachments JSONB DEFAULT '[]',
    approved_by UUID REFERENCES users(id),
    approved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_onboardings_employee ON onboardings(employee_id);
CREATE INDEX idx_onboardings_hr ON onboardings(hr_id);
CREATE INDEX idx_onboardings_status ON onboardings(status);
CREATE INDEX idx_onboarding_tasks_onboarding ON onboarding_tasks(onboarding_id);
CREATE INDEX idx_onboarding_tasks_assignee ON onboarding_tasks(assignee_id);
CREATE INDEX idx_onboarding_tasks_status ON onboarding_tasks(status);
CREATE INDEX idx_onboarding_tasks_deadline ON onboarding_tasks(deadline);
CREATE INDEX idx_documents_onboarding ON documents(onboarding_id);
CREATE INDEX idx_documents_employee ON documents(employee_id);
CREATE INDEX idx_it_jobs_onboarding ON it_automation_jobs(onboarding_id);
CREATE INDEX idx_it_jobs_status ON it_automation_jobs(status);
CREATE INDEX idx_notifications_user ON notifications(user_id);
CREATE INDEX idx_notifications_read ON notifications(user_id, is_read);
CREATE INDEX idx_template_items_template ON template_task_items(template_id);
CREATE INDEX idx_employees_status ON employees(status);
CREATE INDEX idx_self_service_employee ON self_service_requests(employee_id);
