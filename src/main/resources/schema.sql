CREATE TABLE T_ERROR (
    ID BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    ERROR_CODE SMALLINT NOT NULL,
    ERROR_MESSAGE VARCHAR(255),
    MESSAGE VARCHAR(512),
    STATUS CHAR(6)
);