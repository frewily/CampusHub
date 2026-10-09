-- Synthetic rows only; owned by ShopCacheMySqlRedisIT, not the developer database.
CREATE TABLE tb_shop (
 id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT, name VARCHAR(255), type_id BIGINT UNSIGNED,
 merchant_id BIGINT UNSIGNED NULL, images VARCHAR(255), area VARCHAR(255), address VARCHAR(255),
 x DOUBLE, y DOUBLE, avg_price BIGINT, sold INT, comments INT, score INT, open_hours VARCHAR(255),
 create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;
