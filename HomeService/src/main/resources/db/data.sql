INSERT IGNORE INTO service_types (type_code, type_name, description, base_price, is_active, created_at, updated_at) VALUES
('cleaning', '家庭保洁', '家庭日常清洁服务', 100.0, true, NOW(), NOW()),
('nursing', '养老护理', '老年人日常照顾服务', 150.0, true, NOW(), NOW()),
('cooking', '家政烹饪', '上门烹饪服务', 120.0, true, NOW(), NOW()),
('childcare', '育儿服务', '婴幼儿照顾服务', 180.0, true, NOW(), NOW()),
('repair', '家电维修', '家用电器维修服务', 200.0, true, NOW(), NOW());

INSERT IGNORE INTO service_regions (region_code, region_name, province, city, district, is_active, created_at, updated_at) VALUES
('bj-chaoyang', '北京朝阳区', '北京市', '北京市', '朝阳区', true, NOW(), NOW()),
('bj-haidian', '北京海淀区', '北京市', '北京市', '海淀区', true, NOW(), NOW()),
('sh-pudong', '上海浦东新区', '上海市', '上海市', '浦东新区', true, NOW(), NOW()),
('sh-huangpu', '上海黄浦区', '上海市', '上海市', '黄浦区', true, NOW(), NOW()),
('gz-tianhe', '广州天河区', '广东省', '广州市', '天河区', true, NOW(), NOW());
