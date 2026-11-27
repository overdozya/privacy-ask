package io.github.overdozya.privacyask.schema;

import org.testcontainers.mysql.MySQLContainer;

/** 로컬과 같은 MySQL 8.4(06 1.1, U1). */
final class MySqlContainers {
    private MySqlContainers() {}

    static MySQLContainer mysql84() {
        return new MySQLContainer("mysql:8.4")
                .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci",
                        "--default-time-zone=+00:00");
    }
}
