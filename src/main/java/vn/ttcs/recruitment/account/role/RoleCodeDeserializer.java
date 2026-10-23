package vn.ttcs.recruitment.account.role;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import vn.ttcs.recruitment.account.Role;

// A role in a request body must be the exact code, such as "ADMIN". Jackson would otherwise also accept the
// position of the constant (0 or "0" would become ADMIN), so numbers, other text such as "admin" or "CANDIDATE",
// true/false and objects all fail as INVALID_JSON. JSON null never reaches this class; AccountRolesRequest rejects it.
class RoleCodeDeserializer extends ValueDeserializer<Role> {
    @Override
    public Role deserialize(JsonParser parser, DeserializationContext context) {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            return (Role) context.handleUnexpectedToken(Role.class, parser);
        }
        String text = parser.getString();
        for (Role role : Role.values()) {
            if (role.name().equals(text)) {
                return role;
            }
        }
        return (Role) context.handleWeirdStringValue(Role.class, text, "Role must be an internal role code");
    }
}
