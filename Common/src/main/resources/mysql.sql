DROP TABLE IF EXISTS `{prefix}data`;
CREATE TABLE `{prefix}data` (
  `id` tinyint(3) unsigned NOT NULL AUTO_INCREMENT,
  `key` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `value` varchar(1024) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `{prefix}name_UNIQUE` (`key`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `{prefix}levels`;
CREATE TABLE `{prefix}levels` (
  `id` tinyint(3) unsigned NOT NULL AUTO_INCREMENT,
  `name` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `{prefix}name_UNIQUE` (`name`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `{prefix}levels` VALUES (1, 'ALL');

DROP TABLE IF EXISTS `{prefix}servers`;
CREATE TABLE `{prefix}servers` (
  `id` bigint(8) unsigned NOT NULL AUTO_INCREMENT,
  `uuid` char(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(25) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `{prefix}uuid_UNIQUE` (`uuid`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `{prefix}players`;
CREATE TABLE `{prefix}players` (
  `id` bigint(8) unsigned NOT NULL AUTO_INCREMENT,
  `uuid` char(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `{prefix}uuid_UNIQUE` (`uuid`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `{prefix}posted_chat`;
CREATE TABLE `{prefix}posted_chat` (
  `id` bigint(8) unsigned NOT NULL AUTO_INCREMENT,
  `server_id` bigint(8) unsigned NOT NULL,
  `player_id` bigint(8) unsigned NOT NULL,
  `level` tinyint(3) unsigned NOT NULL DEFAULT 1,
  `message` mediumtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `date` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `{prefix}fk_posted_chat_server_id_idx` (`server_id`),
  KEY `{prefix}fk_posted_chat_player_id_idx` (`player_id`),
  KEY `{prefix}fk_posted_chat_level_idx` (`level`),
  CONSTRAINT `{prefix}fk_posted_chat_server_id` FOREIGN KEY (`server_id`) REFERENCES `{prefix}servers` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT `{prefix}fk_posted_chat_player_id` FOREIGN KEY (`player_id`) REFERENCES `{prefix}players` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `{prefix}fk_posted_chat_level` FOREIGN KEY (`level`) REFERENCES `{prefix}levels` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP PROCEDURE IF EXISTS `{prefix}get_messages_player`;
DELIMITER ;;
CREATE PROCEDURE `{prefix}get_messages_player`(`player_id` BIGINT UNSIGNED, `days` INT)
BEGIN
  DECLARE `from` DATETIME DEFAULT DATE_SUB(CURRENT_TIMESTAMP, INTERVAL `days` DAY);
  SET `days` = IFNULL(`days`, 1);
  SELECT
    `c`.`id`,
    `s`.`uuid` AS `server_id`,
    `s`.`name` AS `server_name`,
    `p`.`uuid` AS `player_id`,
    `c`.`level`,
    `l`.`name` AS `level_name`,
    `c`.`message`,
    `c`.`date`
  FROM `{prefix}posted_chat` `c`
  JOIN `{prefix}servers` `s` ON `s`.`id` = `c`.`server_id`
  JOIN `{prefix}players` `p` ON `p`.`id` = `c`.`player_id`
  JOIN `{prefix}levels` `l` ON `l`.`id` = `c`.`level`
  WHERE `c`.`date` >= `from` AND `c`.`player_id` = `player_id`;
END ;;
DELIMITER ;

DROP PROCEDURE IF EXISTS `{prefix}get_queue_date`;
DELIMITER ;;
CREATE PROCEDURE `{prefix}get_queue_date`(`after` DATETIME, `server_id` BIGINT UNSIGNED)
BEGIN
  SELECT
    `c`.`id`,
    `s`.`uuid` AS `server_id`,
    `s`.`name` AS `server_name`,
    `p`.`uuid` AS `player_id`,
    `c`.`level`,
    `l`.`name` AS `level_name`,
    `c`.`message`,
    `c`.`date`
  FROM `{prefix}posted_chat` `c`
  JOIN `{prefix}servers` `s` ON `s`.`id` = `c`.`server_id`
  JOIN `{prefix}players` `p` ON `p`.`id` = `c`.`player_id`
  JOIN `{prefix}levels` `l` ON `l`.`id` = `c`.`level`
  WHERE `server_id` <> `c`.`server_id` AND `c`.`date` > `after`;
END ;;
DELIMITER ;

DROP PROCEDURE IF EXISTS `{prefix}get_queue_id`;
DELIMITER ;;
CREATE PROCEDURE `{prefix}get_queue_id`(`after` BIGINT UNSIGNED, `server_id` BIGINT)
BEGIN
  SELECT
    `c`.`id`,
    `s`.`uuid` AS `server_id`,
    `s`.`name` AS `server_name`,
    `p`.`uuid` AS `player_id`,
    `c`.`level`,
    `l`.`name` AS `level_name`,
    `c`.`message`,
    `c`.`date`
  FROM `{prefix}posted_chat` `c`
  JOIN `{prefix}servers` `s` ON `s`.`id` = `c`.`server_id`
  JOIN `{prefix}players` `p` ON `p`.`id` = `c`.`player_id`
  JOIN `{prefix}levels` `l` ON `l`.`id` = `c`.`level`
  WHERE `server_id` <> `c`.`server_id` AND `c`.`id` > `after`;
END ;;
DELIMITER ;
