INSERT INTO form_definition (form_key, form_name, form_desc, form_schema, version, status, category, creator_id, dept_ids, role_ids) VALUES
('leave_request', '请假申请表', '员工请假申请表单，支持事假、病假、年假、调休等类型', JSON_OBJECT(
    'fields', JSON_ARRAY(
        JSON_OBJECT('key', 'leave_type', 'type', 'select', 'label', '请假类型', 'required', true, 'placeholder', '请选择请假类型',
            'options', JSON_ARRAY(
                JSON_OBJECT('label', '事假', 'value', 'personal'),
                JSON_OBJECT('label', '病假', 'value', 'sick'),
                JSON_OBJECT('label', '年假', 'value', 'annual'),
                JSON_OBJECT('label', '调休', 'value', 'compensatory'),
                JSON_OBJECT('label', '婚假', 'value', 'marriage'),
                JSON_OBJECT('label', '产假', 'value', 'maternity')
            )
        ),
        JSON_OBJECT('key', 'leave_reason', 'type', 'textarea', 'label', '请假事由', 'required', true, 'placeholder', '请详细说明请假原因', 'maxLength', 500),
        JSON_OBJECT('key', 'date_range', 'type', 'date_range', 'label', '请假时间', 'required', true, 'format', 'yyyy-MM-dd'),
        JSON_OBJECT('key', 'leave_days', 'type', 'number', 'label', '请假天数', 'required', true, 'min', 0.5, 'max', 30, 'step', 0.5, 'unit', '天'),
        JSON_OBJECT('key', 'emergency_contact', 'type', 'phone', 'label', '紧急联系电话', 'required', false, 'placeholder', '请输入紧急联系电话'),
        JSON_OBJECT('key', 'attachment', 'type', 'file_upload', 'label', '附件', 'required', false, 'accept', '.pdf,.doc,.docx,.jpg,.png', 'maxSize', '10MB', 'maxCount', 3, 'description', '病假需上传医院证明')
    ),
    'layout', JSON_OBJECT(
        'columns', 2,
        'labelWidth', '120px',
        'labelPosition', 'right'
    ),
    'rules', JSON_ARRAY(
        JSON_OBJECT('condition', 'leave_type === "sick"', 'action', 'show', 'target', 'attachment'),
        JSON_OBJECT('condition', 'leave_type === "sick"', 'action', 'required', 'target', 'attachment')
    )
), 1, 1, 'HR', 1, '[1, 2, 3, 4, 5]', '[5]'),

('expense_reimbursement', '费用报销单', '员工日常费用报销申请表单，涵盖差旅、办公、交通等费用', JSON_OBJECT(
    'fields', JSON_ARRAY(
        JSON_OBJECT('key', 'reimburse_category', 'type', 'select', 'label', '报销类别', 'required', true, 'placeholder', '请选择报销类别',
            'options', JSON_ARRAY(
                JSON_OBJECT('label', '差旅费', 'value', 'travel'),
                JSON_OBJECT('label', '办公费', 'value', 'office'),
                JSON_OBJECT('label', '交通费', 'value', 'transport'),
                JSON_OBJECT('label', '招待费', 'value', 'entertainment'),
                JSON_OBJECT('label', '培训费', 'value', 'training'),
                JSON_OBJECT('label', '其他', 'value', 'other')
            )
        ),
        JSON_OBJECT('key', 'expense_details', 'type', 'sub_form', 'label', '费用明细', 'required', true,
            'children', JSON_ARRAY(
                JSON_OBJECT('key', 'expense_date', 'type', 'date_picker', 'label', '费用日期', 'required', true, 'format', 'yyyy-MM-dd'),
                JSON_OBJECT('key', 'expense_item', 'type', 'text', 'label', '费用项目', 'required', true, 'placeholder', '如：北京-上海机票'),
                JSON_OBJECT('key', 'expense_amount', 'type', 'amount', 'label', '金额', 'required', true, 'currency', 'CNY', 'min', 0.01),
                JSON_OBJECT('key', 'invoice_count', 'type', 'number', 'label', '票据张数', 'required', true, 'min', 1)
            ),
            'minRows', 1, 'maxRows', 20
        ),
        JSON_OBJECT('key', 'total_amount', 'type', 'formula', 'label', '合计金额', 'required', true, 'readonly', true,
            'expression', 'SUM(expense_details.expense_amount)', 'currency', 'CNY'
        ),
        JSON_OBJECT('key', 'payment_method', 'type', 'radio', 'label', '付款方式', 'required', true,
            'options', JSON_ARRAY(
                JSON_OBJECT('label', '银行转账', 'value', 'bank_transfer'),
                JSON_OBJECT('label', '现金', 'value', 'cash')
            )
        ),
        JSON_OBJECT('key', 'bank_account', 'type', 'text', 'label', '收款账号', 'required', false, 'placeholder', '请输入银行账号', 'pattern', '^\\d{16,19}$'),
        JSON_OBJECT('key', 'bank_name', 'type', 'text', 'label', '开户行', 'required', false, 'placeholder', '如：中国工商银行XX支行'),
        JSON_OBJECT('key', 'invoices', 'type', 'file_upload', 'label', '发票附件', 'required', true, 'accept', '.pdf,.jpg,.png', 'maxSize', '20MB', 'maxCount', 10, 'description', '请上传发票扫描件或照片')
    ),
    'layout', JSON_OBJECT(
        'columns', 2,
        'labelWidth', '120px',
        'labelPosition', 'right'
    ),
    'rules', JSON_ARRAY(
        JSON_OBJECT('condition', 'payment_method === "bank_transfer"', 'action', 'show', 'target', 'bank_account'),
        JSON_OBJECT('condition', 'payment_method === "bank_transfer"', 'action', 'required', 'target', 'bank_account'),
        JSON_OBJECT('condition', 'payment_method === "bank_transfer"', 'action', 'show', 'target', 'bank_name'),
        JSON_OBJECT('condition', 'payment_method === "bank_transfer"', 'action', 'required', 'target', 'bank_name')
    )
), 1, 1, 'FIN', 1, '[1, 2, 3, 4, 5]', '[5]'),

('purchase_request', '采购申请单', '部门物资采购申请表单，用于办公设备、耗材等采购审批', JSON_OBJECT(
    'fields', JSON_ARRAY(
        JSON_OBJECT('key', 'purchase_urgency', 'type', 'radio', 'label', '紧急程度', 'required', true,
            'options', JSON_ARRAY(
                JSON_OBJECT('label', '普通', 'value', 'normal'),
                JSON_OBJECT('label', '紧急', 'value', 'urgent'),
                JSON_OBJECT('label', '特急', 'value', 'critical')
            )
        ),
        JSON_OBJECT('key', 'purchase_reason', 'type', 'textarea', 'label', '采购原因', 'required', true, 'placeholder', '请说明采购背景和原因', 'maxLength', 1000),
        JSON_OBJECT('key', 'item_list', 'type', 'sub_form', 'label', '采购清单', 'required', true,
            'children', JSON_ARRAY(
                JSON_OBJECT('key', 'item_name', 'type', 'text', 'label', '物品名称', 'required', true, 'placeholder', '如：笔记本电脑'),
                JSON_OBJECT('key', 'item_spec', 'type', 'text', 'label', '规格型号', 'required', true, 'placeholder', '如：ThinkPad X1 Carbon Gen 11'),
                JSON_OBJECT('key', 'item_quantity', 'type', 'number', 'label', '数量', 'required', true, 'min', 1),
                JSON_OBJECT('key', 'item_unit_price', 'type', 'amount', 'label', '单价（含税）', 'required', true, 'currency', 'CNY', 'min', 0.01),
                JSON_OBJECT('key', 'item_subtotal', 'type', 'formula', 'label', '小计', 'readonly', true, 'expression', 'item_quantity * item_unit_price', 'currency', 'CNY'),
                JSON_OBJECT('key', 'item_remark', 'type', 'textarea', 'label', '备注', 'required', false, 'maxLength', 200)
            ),
            'minRows', 1, 'maxRows', 50
        ),
        JSON_OBJECT('key', 'total_budget', 'type', 'formula', 'label', '采购总金额', 'required', true, 'readonly', true,
            'expression', 'SUM(item_list.item_subtotal)', 'currency', 'CNY'
        ),
        JSON_OBJECT('key', 'expected_date', 'type', 'date_picker', 'label', '期望到货日期', 'required', true, 'format', 'yyyy-MM-dd'),
        JSON_OBJECT('key', 'delivery_address', 'type', 'text', 'label', '收货地址', 'required', true, 'placeholder', '请填写详细收货地址'),
        JSON_OBJECT('key', 'receiver_name', 'type', 'text', 'label', '收货人', 'required', true, 'placeholder', '请填写收货人姓名'),
        JSON_OBJECT('key', 'receiver_phone', 'type', 'phone', 'label', '收货人电话', 'required', true, 'placeholder', '请填写联系电话'),
        JSON_OBJECT('key', 'reference_docs', 'type', 'file_upload', 'label', '参考文档', 'required', false, 'accept', '.pdf,.doc,.docx,.xls,.xlsx', 'maxSize', '20MB', 'maxCount', 5, 'description', '可选：报价单、需求文档等')
    ),
    'layout', JSON_OBJECT(
        'columns', 2,
        'labelWidth', '120px',
        'labelPosition', 'right'
    ),
    'rules', JSON_ARRAY()
), 1, 1, 'OPS', 1, '[1, 2, 3, 4, 5]', '[2, 3, 4, 5]');

INSERT INTO process_definition (process_key, process_name, process_desc, form_id, bpmn_xml, process_data, version, status, category, creator_id) VALUES
('leave_approval', '请假审批流程', '员工请假申请审批流程：员工提交 → 直属主管审批 → 部门经理审批（≥3天）→ HR备案', 1,
'<?xml version="1.0" encoding="UTF-8"?><bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" id="leave_approval" targetNamespace="http://flowplatform.com/process"><bpmn:process id="leave_approval" name="请假审批流程" isExecutable="true"><bpmn:startEvent id="start" name="提交申请"/><bpmn:userTask id="task_direct_approve" name="直属主管审批" flowable:assignee="${initiator.managerId}"/><bpmn:exclusiveGateway id="gw_days" name="天数判断"/><bpmn:userTask id="task_dept_approve" name="部门经理审批" flowable:candidateGroups="DEPT_MANAGER"/><bpmn:userTask id="task_hr_record" name="HR备案" flowable:candidateGroups="HR"/><bpmn:endEvent id="end" name="结束"/><bpmn:sequenceFlow sourceRef="start" targetRef="task_direct_approve"/><bpmn:sequenceFlow sourceRef="task_direct_approve" targetRef="gw_days"/><bpmn:sequenceFlow sourceRef="gw_days" targetRef="task_dept_approve"><bpmn:conditionExpression>leave_days >= 3</bpmn:conditionExpression></bpmn:sequenceFlow><bpmn:sequenceFlow sourceRef="gw_days" targetRef="task_hr_record"><bpmn:conditionExpression>leave_days &lt; 3</bpmn:conditionExpression></bpmn:sequenceFlow><bpmn:sequenceFlow sourceRef="task_dept_approve" targetRef="task_hr_record"/><bpmn:sequenceFlow sourceRef="task_hr_record" targetRef="end"/></bpmn:process></bpmn:definitions>',
JSON_OBJECT(
    'nodes', JSON_ARRAY(
        JSON_OBJECT('id', 'start', 'name', '提交申请', 'type', 'start'),
        JSON_OBJECT('id', 'task_direct_approve', 'name', '直属主管审批', 'type', 'approval', 'assigneeType', 'initiator_manager',
            'actions', JSON_ARRAY('APPROVE', 'REJECT', 'RETURN'),
            'timeout', JSON_OBJECT('duration', 24, 'unit', 'hours', 'autoAction', 'REMIND')
        ),
        JSON_OBJECT('id', 'gw_days', 'name', '天数判断', 'type', 'gateway', 'conditionField', 'leave_days'),
        JSON_OBJECT('id', 'task_dept_approve', 'name', '部门经理审批', 'type', 'approval', 'assigneeType', 'role', 'assigneeRole', 'DEPT_MANAGER',
            'actions', JSON_ARRAY('APPROVE', 'REJECT', 'RETURN'),
            'timeout', JSON_OBJECT('duration', 48, 'unit', 'hours', 'autoAction', 'REMIND')
        ),
        JSON_OBJECT('id', 'task_hr_record', 'name', 'HR备案', 'type', 'cc', 'assigneeType', 'dept', 'assigneeDept', 'HR',
            'actions', JSON_ARRAY('APPROVE')
        ),
        JSON_OBJECT('id', 'end', 'name', '结束', 'type', 'end')
    ),
    'edges', JSON_ARRAY(
        JSON_OBJECT('source', 'start', 'target', 'task_direct_approve'),
        JSON_OBJECT('source', 'task_direct_approve', 'target', 'gw_days', 'condition', 'action === "APPROVE"'),
        JSON_OBJECT('source', 'gw_days', 'target', 'task_dept_approve', 'condition', 'leave_days >= 3'),
        JSON_OBJECT('source', 'gw_days', 'target', 'task_hr_record', 'condition', 'leave_days < 3'),
        JSON_OBJECT('source', 'task_dept_approve', 'target', 'task_hr_record', 'condition', 'action === "APPROVE"'),
        JSON_OBJECT('source', 'task_hr_record', 'target', 'end')
    )
), 1, 1, 'HR', 1),

('expense_approval', '费用报销审批流程', '费用报销审批流程：员工提交 → 直属主管审批 → 财务审核 → 出纳付款', 2,
'<?xml version="1.0" encoding="UTF-8"?><bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" id="expense_approval" targetNamespace="http://flowplatform.com/process"><bpmn:process id="expense_approval" name="费用报销审批流程" isExecutable="true"><bpmn:startEvent id="start" name="提交报销"/><bpmn:userTask id="task_manager_approve" name="主管审批" flowable:assignee="${initiator.managerId}"/><bpmn:exclusiveGateway id="gw_amount" name="金额判断"/><bpmn:userTask id="task_finance_review" name="财务审核" flowable:candidateGroups="FIN"/><bpmn:userTask id="task_finance_director" name="财务总监审批" flowable:candidateGroups="FIN_DIRECTOR"/><bpmn:userTask id="task_cashier_pay" name="出纳付款" flowable:candidateGroups="CASHIER"/><bpmn:endEvent id="end" name="报销完成"/><bpmn:sequenceFlow sourceRef="start" targetRef="task_manager_approve"/><bpmn:sequenceFlow sourceRef="task_manager_approve" targetRef="gw_amount"/><bpmn:sequenceFlow sourceRef="gw_amount" targetRef="task_finance_review"><bpmn:conditionExpression>total_amount &lt; 5000</bpmn:conditionExpression></bpmn:sequenceFlow><bpmn:sequenceFlow sourceRef="gw_amount" targetRef="task_finance_director"><bpmn:conditionExpression>total_amount >= 5000</bpmn:conditionExpression></bpmn:sequenceFlow><bpmn:sequenceFlow sourceRef="task_finance_review" targetRef="task_cashier_pay"/><bpmn:sequenceFlow sourceRef="task_finance_director" targetRef="task_cashier_pay"/><bpmn:sequenceFlow sourceRef="task_cashier_pay" targetRef="end"/></bpmn:process></bpmn:definitions>',
JSON_OBJECT(
    'nodes', JSON_ARRAY(
        JSON_OBJECT('id', 'start', 'name', '提交报销', 'type', 'start'),
        JSON_OBJECT('id', 'task_manager_approve', 'name', '主管审批', 'type', 'approval', 'assigneeType', 'initiator_manager',
            'actions', JSON_ARRAY('APPROVE', 'REJECT', 'RETURN'),
            'timeout', JSON_OBJECT('duration', 24, 'unit', 'hours', 'autoAction', 'REMIND')
        ),
        JSON_OBJECT('id', 'gw_amount', 'name', '金额判断', 'type', 'gateway', 'conditionField', 'total_amount'),
        JSON_OBJECT('id', 'task_finance_review', 'name', '财务审核', 'type', 'approval', 'assigneeType', 'dept', 'assigneeDept', 'FIN',
            'actions', JSON_ARRAY('APPROVE', 'REJECT', 'RETURN'),
            'timeout', JSON_OBJECT('duration', 48, 'unit', 'hours', 'autoAction', 'REMIND')
        ),
        JSON_OBJECT('id', 'task_finance_director', 'name', '财务总监审批', 'type', 'approval', 'assigneeType', 'role', 'assigneeRole', 'FIN_DIRECTOR',
            'actions', JSON_ARRAY('APPROVE', 'REJECT', 'RETURN'),
            'timeout', JSON_OBJECT('duration', 72, 'unit', 'hours', 'autoAction', 'REMIND')
        ),
        JSON_OBJECT('id', 'task_cashier_pay', 'name', '出纳付款', 'type', 'approval', 'assigneeType', 'role', 'assigneeRole', 'CASHIER',
            'actions', JSON_ARRAY('APPROVE')
        ),
        JSON_OBJECT('id', 'end', 'name', '报销完成', 'type', 'end')
    ),
    'edges', JSON_ARRAY(
        JSON_OBJECT('source', 'start', 'target', 'task_manager_approve'),
        JSON_OBJECT('source', 'task_manager_approve', 'target', 'gw_amount', 'condition', 'action === "APPROVE"'),
        JSON_OBJECT('source', 'gw_amount', 'target', 'task_finance_review', 'condition', 'total_amount < 5000'),
        JSON_OBJECT('source', 'gw_amount', 'target', 'task_finance_director', 'condition', 'total_amount >= 5000'),
        JSON_OBJECT('source', 'task_finance_review', 'target', 'task_cashier_pay', 'condition', 'action === "APPROVE"'),
        JSON_OBJECT('source', 'task_finance_director', 'target', 'task_cashier_pay', 'condition', 'action === "APPROVE"'),
        JSON_OBJECT('source', 'task_cashier_pay', 'target', 'end')
    )
), 1, 1, 'FIN', 1),

('purchase_approval', '采购审批流程', '采购申请审批流程：申请人提交 → 部门经理审批 → 采购部审核 → 总经理审批（≥50000元）→ 采购执行', 3,
'<?xml version="1.0" encoding="UTF-8"?><bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" id="purchase_approval" targetNamespace="http://flowplatform.com/process"><bpmn:process id="purchase_approval" name="采购审批流程" isExecutable="true"><bpmn:startEvent id="start" name="提交采购申请"/><bpmn:userTask id="task_dept_manager" name="部门经理审批" flowable:candidateGroups="DEPT_MANAGER"/><bpmn:userTask id="task_procurement_review" name="采购部审核" flowable:candidateGroups="PROCUREMENT"/><bpmn:exclusiveGateway id="gw_budget" name="预算判断"/><bpmn:userTask id="task_ceo_approve" name="总经理审批" flowable:candidateGroups="CEO"/><bpmn:userTask id="task_procurement_execute" name="采购执行" flowable:candidateGroups="PROCUREMENT"/><bpmn:endEvent id="end" name="采购完成"/><bpmn:sequenceFlow sourceRef="start" targetRef="task_dept_manager"/><bpmn:sequenceFlow sourceRef="task_dept_manager" targetRef="task_procurement_review"/><bpmn:sequenceFlow sourceRef="task_procurement_review" targetRef="gw_budget"/><bpmn:sequenceFlow sourceRef="gw_budget" targetRef="task_ceo_approve"><bpmn:conditionExpression>total_budget >= 50000</bpmn:conditionExpression></bpmn:sequenceFlow><bpmn:sequenceFlow sourceRef="gw_budget" targetRef="task_procurement_execute"><bpmn:conditionExpression>total_budget &lt; 50000</bpmn:conditionExpression></bpmn:sequenceFlow><bpmn:sequenceFlow sourceRef="task_ceo_approve" targetRef="task_procurement_execute"/><bpmn:sequenceFlow sourceRef="task_procurement_execute" targetRef="end"/></bpmn:process></bpmn:definitions>',
JSON_OBJECT(
    'nodes', JSON_ARRAY(
        JSON_OBJECT('id', 'start', 'name', '提交采购申请', 'type', 'start'),
        JSON_OBJECT('id', 'task_dept_manager', 'name', '部门经理审批', 'type', 'approval', 'assigneeType', 'role', 'assigneeRole', 'DEPT_MANAGER',
            'actions', JSON_ARRAY('APPROVE', 'REJECT', 'RETURN'),
            'timeout', JSON_OBJECT('duration', 48, 'unit', 'hours', 'autoAction', 'REMIND')
        ),
        JSON_OBJECT('id', 'task_procurement_review', 'name', '采购部审核', 'type', 'approval', 'assigneeType', 'dept', 'assigneeDept', 'PROCUREMENT',
            'actions', JSON_ARRAY('APPROVE', 'REJECT', 'RETURN'),
            'timeout', JSON_OBJECT('duration', 72, 'unit', 'hours', 'autoAction', 'REMIND')
        ),
        JSON_OBJECT('id', 'gw_budget', 'name', '预算判断', 'type', 'gateway', 'conditionField', 'total_budget'),
        JSON_OBJECT('id', 'task_ceo_approve', 'name', '总经理审批', 'type', 'approval', 'assigneeType', 'role', 'assigneeRole', 'CEO',
            'actions', JSON_ARRAY('APPROVE', 'REJECT', 'RETURN'),
            'timeout', JSON_OBJECT('duration', 120, 'unit', 'hours', 'autoAction', 'REMIND')
        ),
        JSON_OBJECT('id', 'task_procurement_execute', 'name', '采购执行', 'type', 'approval', 'assigneeType', 'dept', 'assigneeDept', 'PROCUREMENT',
            'actions', JSON_ARRAY('APPROVE')
        ),
        JSON_OBJECT('id', 'end', 'name', '采购完成', 'type', 'end')
    ),
    'edges', JSON_ARRAY(
        JSON_OBJECT('source', 'start', 'target', 'task_dept_manager'),
        JSON_OBJECT('source', 'task_dept_manager', 'target', 'task_procurement_review', 'condition', 'action === "APPROVE"'),
        JSON_OBJECT('source', 'task_procurement_review', 'target', 'gw_budget', 'condition', 'action === "APPROVE"'),
        JSON_OBJECT('source', 'gw_budget', 'target', 'task_ceo_approve', 'condition', 'total_budget >= 50000'),
        JSON_OBJECT('source', 'gw_budget', 'target', 'task_procurement_execute', 'condition', 'total_budget < 50000'),
        JSON_OBJECT('source', 'task_ceo_approve', 'target', 'task_procurement_execute', 'condition', 'action === "APPROVE"'),
        JSON_OBJECT('source', 'task_procurement_execute', 'target', 'end')
    )
), 1, 1, 'OPS', 1);

INSERT INTO process_node_config (process_id, node_id, node_name, node_type, node_config, sort_order) VALUES
(1, 'task_direct_approve', '直属主管审批', 'approval', JSON_OBJECT(
    'assigneeType', 'initiator_manager',
    'multiInstanceType', 'single',
    'allowTransfer', true,
    'allowDelegate', true,
    'timeoutConfig', JSON_OBJECT('duration', 24, 'unit', 'hours', 'autoAction', 'REMIND', 'remindInterval', 4)
), 1),
(1, 'task_dept_approve', '部门经理审批', 'approval', JSON_OBJECT(
    'assigneeType', 'role',
    'assigneeRole', 'DEPT_MANAGER',
    'multiInstanceType', 'single',
    'allowTransfer', true,
    'timeoutConfig', JSON_OBJECT('duration', 48, 'unit', 'hours', 'autoAction', 'REMIND', 'remindInterval', 8)
), 2),
(1, 'task_hr_record', 'HR备案', 'cc', JSON_OBJECT(
    'assigneeType', 'dept',
    'assigneeDept', 'HR',
    'autoComplete', true
), 3),
(2, 'task_manager_approve', '主管审批', 'approval', JSON_OBJECT(
    'assigneeType', 'initiator_manager',
    'multiInstanceType', 'single',
    'allowTransfer', true,
    'timeoutConfig', JSON_OBJECT('duration', 24, 'unit', 'hours', 'autoAction', 'REMIND', 'remindInterval', 4)
), 1),
(2, 'task_finance_review', '财务审核', 'approval', JSON_OBJECT(
    'assigneeType', 'dept',
    'assigneeDept', 'FIN',
    'multiInstanceType', 'single',
    'allowTransfer', true,
    'timeoutConfig', JSON_OBJECT('duration', 48, 'unit', 'hours', 'autoAction', 'REMIND', 'remindInterval', 8)
), 2),
(2, 'task_finance_director', '财务总监审批', 'approval', JSON_OBJECT(
    'assigneeType', 'role',
    'assigneeRole', 'FIN_DIRECTOR',
    'multiInstanceType', 'single',
    'allowTransfer', true,
    'timeoutConfig', JSON_OBJECT('duration', 72, 'unit', 'hours', 'autoAction', 'REMIND', 'remindInterval', 12)
), 3),
(2, 'task_cashier_pay', '出纳付款', 'approval', JSON_OBJECT(
    'assigneeType', 'role',
    'assigneeRole', 'CASHIER',
    'multiInstanceType', 'single'
), 4),
(3, 'task_dept_manager', '部门经理审批', 'approval', JSON_OBJECT(
    'assigneeType', 'role',
    'assigneeRole', 'DEPT_MANAGER',
    'multiInstanceType', 'single',
    'allowTransfer', true,
    'timeoutConfig', JSON_OBJECT('duration', 48, 'unit', 'hours', 'autoAction', 'REMIND', 'remindInterval', 8)
), 1),
(3, 'task_procurement_review', '采购部审核', 'approval', JSON_OBJECT(
    'assigneeType', 'dept',
    'assigneeDept', 'PROCUREMENT',
    'multiInstanceType', 'single',
    'allowTransfer', true,
    'timeoutConfig', JSON_OBJECT('duration', 72, 'unit', 'hours', 'autoAction', 'REMIND', 'remindInterval', 12)
), 2),
(3, 'task_ceo_approve', '总经理审批', 'approval', JSON_OBJECT(
    'assigneeType', 'role',
    'assigneeRole', 'CEO',
    'multiInstanceType', 'single',
    'allowTransfer', true,
    'timeoutConfig', JSON_OBJECT('duration', 120, 'unit', 'hours', 'autoAction', 'REMIND', 'remindInterval', 24)
), 3),
(3, 'task_procurement_execute', '采购执行', 'approval', JSON_OBJECT(
    'assigneeType', 'dept',
    'assigneeDept', 'PROCUREMENT',
    'multiInstanceType', 'single'
), 4);
