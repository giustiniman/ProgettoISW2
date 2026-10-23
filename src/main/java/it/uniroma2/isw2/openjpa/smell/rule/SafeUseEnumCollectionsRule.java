package it.uniroma2.isw2.openjpa.smell.rule;

import net.sourceforge.pmd.lang.java.ast.ASTConstructorCall;
import net.sourceforge.pmd.lang.java.rule.AbstractJavaRulechainRule;
import net.sourceforge.pmd.lang.java.symbols.JClassSymbol;
import net.sourceforge.pmd.lang.java.symbols.JTypeDeclSymbol;
import net.sourceforge.pmd.lang.java.types.JClassType;
import net.sourceforge.pmd.lang.java.types.JTypeMirror;
import net.sourceforge.pmd.lang.java.types.TypeTestUtil;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * Compatibility implementation of PMD's UseEnumCollections rule.
 * PMD 7.7 assumes that every non-raw HashMap/HashSet type has at least one
 * resolved type argument. Old, incomplete source snapshots can violate that
 * assumption, so this version skips only the indeterminate constructor call.
 */
public final class SafeUseEnumCollectionsRule extends AbstractJavaRulechainRule {

    public SafeUseEnumCollectionsRule() {
        super(ASTConstructorCall.class);
    }

    @Override
    public Object visit(ASTConstructorCall call, Object data) {
        JTypeMirror builtType = call.getTypeMirror();
        if (builtType.isRaw() || !(builtType instanceof JClassType classType)) {
            return null;
        }

        boolean isMap = TypeTestUtil.isExactlyA(HashMap.class, builtType);
        if (!isMap && !TypeTestUtil.isExactlyA(HashSet.class, builtType)) {
            return null;
        }

        List<JTypeMirror> typeArguments = classType.getTypeArgs();
        if (typeArguments.isEmpty()) {
            return null;
        }

        JTypeDeclSymbol keySymbol = typeArguments.get(0).getSymbol();
        if (keySymbol instanceof JClassSymbol classSymbol && classSymbol.isEnum()) {
            asCtx(data).addViolation(call.getTypeNode(), isMap ? "EnumMap" : "EnumSet");
        }
        return null;
    }
}
