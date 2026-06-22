rootProject.name = "kore"

include(
    "kore-core",
    "kore-mcp",
    "kore-llm",
    "kore-test",
    "kore-observability",
    "kore-storage",
    "kore-skills",
    "kore-spring",
    "kore-dashboard",
    "kore-kafka",
    "kore-rabbitmq",
    "kore-budget",
    "kore-bom", // NEW (KORE-03 / D-05): java-platform BOM aligning the curated subset
)
