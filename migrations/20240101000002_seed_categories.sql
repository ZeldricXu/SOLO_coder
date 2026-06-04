-- Add seed categories for auction platform

INSERT INTO categories (id, name, slug) VALUES
    ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', '数码电子', 'digital-electronics'),
    ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12', '奢侈品', 'luxury-goods'),
    ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a13', '艺术品', 'artwork'),
    ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a14', '收藏品', 'collectibles'),
    ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a15', '汽车', 'automotive')
ON CONFLICT (slug) DO NOTHING;
