package vn.ttcs.recruitment.competency;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

// status must be the exact text "DRAFT" or "ACTIVE". Jackson would otherwise also accept a JSON number as the
// position of the constant (1 would become ACTIVE), so numbers, other text such as "active", true/false and objects
// all fail as INVALID_JSON. JSON null never reaches this class: it stays null, which means "no status".
class FrameworkStatusDeserializer extends ValueDeserializer<CompetencyFrameworkStatus> {
    @Override
    public CompetencyFrameworkStatus deserialize(JsonParser parser, DeserializationContext context) {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            return (CompetencyFrameworkStatus) context.handleUnexpectedToken(CompetencyFrameworkStatus.class, parser);
        }
        String text = parser.getString();
        for (CompetencyFrameworkStatus status : CompetencyFrameworkStatus.values()) {
            if (status.name().equals(text)) {
                return status;
            }
        }
        return (CompetencyFrameworkStatus) context.handleWeirdStringValue(CompetencyFrameworkStatus.class, text,
                "Status must be DRAFT or ACTIVE");
    }
}
