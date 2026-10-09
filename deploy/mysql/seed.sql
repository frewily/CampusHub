-- Synthetic, disposable local demo records only.
INSERT INTO tb_shop_type (id, name, icon, sort)
VALUES (1, '校园餐饮', '/types/campus-food.png', 1);

INSERT INTO tb_shop
    (id, name, type_id, images, area, address, x, y, avg_price, sold, comments, score, open_hours)
VALUES
    (1, '校园示例食堂', 1, '/images/demo-campus-cafe.png', '校园生活区', '校园示例地址',
     120.000000, 30.000000, 20, 0, 0, 45, '08:00-20:00');
