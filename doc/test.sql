/*
 Navicat Premium Data Transfer

 Source Server         : local5.7
 Source Server Type    : MySQL
 Source Server Version : 50725
 Source Host           : localhost:3306
 Source Schema         : es_data

 Target Server Type    : MySQL
 Target Server Version : 50725
 File Encoding         : 65001

 Date: 15/01/2022 18:46:56
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for t_es_synonym_dic
-- ----------------------------
DROP TABLE IF EXISTS `t_es_synonym_dic`;
CREATE TABLE `t_es_synonym_dic`  (
                                     `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '自增id',
                                     `words` text CHARACTER SET utf8 COLLATE utf8_general_ci NULL COMMENT '同义词项',
                                     `type` varchar(100) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT '' COMMENT '同义词类型',
                                     `status` tinyint(4) NULL DEFAULT 0 COMMENT '0表示未删除，1表示删除',
                                     `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                     `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                     PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 10 CHARACTER SET = utf8 COLLATE = utf8_general_ci ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
