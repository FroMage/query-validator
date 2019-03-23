package org.hibernate.query.validator;

import antlr.SemanticException;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import org.hibernate.hql.internal.ast.DetailedSemanticException;
import org.hibernate.hql.internal.ast.tree.ConstructorNode;
import org.hibernate.hql.internal.ast.tree.PathNode;
import org.hibernate.type.BasicType;
import org.hibernate.type.EntityType;
import org.hibernate.type.Type;

import java.util.List;

import static org.hibernate.query.validator.JavacHelper.lookupEntity;
import static org.hibernate.query.validator.JavacHelper.lookupQualifiedClass;

public class WorkaroundConstructorNode extends ConstructorNode {
    @Override
    public void prepare() throws SemanticException {
        try {
            super.prepare();
        }
        catch (DetailedSemanticException dse) {
            //Ugh, ConstructorNode throws an exception when
            //it tries to load the class and can't!
            String path = ((PathNode) getFirstChild()).getPath();
            ClassSymbol symbol = lookupQualifiedClass(path);
            if (symbol==null) {
                throw new DetailedSemanticException("Class " + path + " not found");
            }
            List<Type> argumentTypeList = getConstructorArgumentTypeList();
            for (Symbol cons: symbol.members().getElements(Symbol::isConstructor)) {
                MethodSymbol constructor = (MethodSymbol) cons;
                if (constructor.params.length()==argumentTypeList.size()) {
                    boolean argumentsCheckOut = true;
                    for (int i=0; i<argumentTypeList.size(); i++) {
                        Type type = argumentTypeList.get(i);
                        Symbol.VarSymbol param = constructor.params.get(i);
                        Symbol.ClassSymbol typeClass;
                        if (type instanceof EntityType) {
                            String entityName = ((EntityType) type).getAssociatedEntityName();
                            typeClass = lookupEntity(entityName);
                        }
                        else if (type instanceof BasicType) {
                            String className;
                            try {
                                className = type.getReturnedClass().getName();
                            }
                            catch (Exception e) {
                                continue;
                            }
                            typeClass = lookupQualifiedClass(className);
                        }
                        else {
                            //TODO!!!
                            continue;
                        }
                        if (typeClass!=null
                                && JavacHelper.isAssignable(param, typeClass)) {
                            argumentsCheckOut = false;
                            break;
                        }
                    }
                    if (argumentsCheckOut) return; //matching constructor found!
                }
            }
            throw new DetailedSemanticException("No suitable constructor for class " + path);
        }
    }

}