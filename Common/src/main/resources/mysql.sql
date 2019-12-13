CREATE DATABASE  IF NOT EXISTS `{database}` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci */ /*!80016 DEFAULT ENCRYPTION='N' */;
USE `{database}`;

CREATE TABLE IF NOT EXISTS `{prefix}levels` (
  `id` smallint(6) unsigned NOT NULL AUTO_INCREMENT,
  `name` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `{prefix}name_UNIQUE` (`name`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO `{prefix}levels` VALUES (1,'ALL');

CREATE TABLE IF NOT EXISTS `{prefix}players` (
  `id` bigint(8) unsigned NOT NULL AUTO_INCREMENT,
  `uuid` char(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `{prefix}uuid_UNIQUE` (`uuid`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `{prefix}posted_chat` (
  `id` bigint(8) unsigned NOT NULL AUTO_INCREMENT,
  `player_id` bigint(8) unsigned NOT NULL,
  `level` smallint(6) unsigned NOT NULL DEFAULT 1,
  `message` mediumtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `date` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `{prefix}fk_posted_chat_id_idx` (`player_id`),
  KEY `{prefix}fk_posted_chat_level_idx` (`level`),
  CONSTRAINT `{prefix}fk_posted_chat_level` FOREIGN KEY (`level`) REFERENCES `{prefix}levels` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT `{prefix}fk_posted_chat_player_id` FOREIGN KEY (`player_id`) REFERENCES `{prefix}players` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DELIMITER ;;
CREATE PROCEDURE IF NOT EXISTS `{prefix}get_messages_uuid`(`uuid` CHAR(36))
BEGIN
  SELECT
    `c`.`id`,
    `p`.`uuid`,
    `c`.`level`,
    `l`.`name` AS `level_name`,
    `c`.`message`,
    `c`.`date`
  FROM `{prefix}posted_chat` `c`
  JOIN `{prefix}players` `p` ON `p`.`id`=`c`.`player_id`
  JOIN `{prefix}levels` `l` ON `l`.`id`=`c`.`level`
  WHERE `p`.`uuid`=`uuid`;
END ;;
DELIMITER ;

DELIMITER ;;
CREATE PROCEDURE IF NOT EXISTS `{prefix}get_queue_date`(`after` DATETIME)
BEGIN
  SELECT
    `c`.`id`,
    `p`.`uuid`,
    `c`.`level`,
    `l`.`name` AS `level_name`,
    `c`.`message`,
    `c`.`date`
  FROM `{prefix}posted_chat` `c`
  JOIN `{prefix}players` `p` ON `p`.`id`=`c`.`player_id`
  JOIN `{prefix}levels` `l` ON `l`.`id`=`c`.`level`
  WHERE `c`.`date`>`after`;
END ;;
DELIMITER ;

DELIMITER ;;
CREATE PROCEDURE IF NOT EXISTS `{prefix}get_queue_id`(`after` BIGINT(8) UNSIGNED)
BEGIN
  SELECT
    `c`.`id`,
    `p`.`uuid`,
    `c`.`level`,
    `l`.`name` AS `level_name`,
    `c`.`message`,
    `c`.`date`
  FROM `{prefix}posted_chat` `c`
  JOIN `{prefix}players` `p` ON `p`.`id`=`c`.`player_id`
  JOIN `{prefix}levels` `l` ON `l`.`id`=`c`.`level`
  WHERE `c`.`id`>`after`;
END ;;
DELIMITER ;
