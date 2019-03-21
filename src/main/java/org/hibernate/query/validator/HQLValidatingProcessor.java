package org.hibernate.query.validator;

import antlr.RecognitionException;
import com.google.auto.service.AutoService;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.model.JavacElements;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeScanner;
import com.sun.tools.javac.util.Names;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.hql.internal.ast.HqlSqlWalker;
import org.hibernate.hql.internal.ast.ParseErrorHandler;
import org.hibernate.hql.internal.ast.QueryTranslatorImpl;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.Name;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import java.lang.reflect.Field;
import java.util.Set;

import static java.util.Collections.emptyMap;

@SupportedAnnotationTypes("org.hibernate.query.validator.CheckHQL")
@AutoService(Processor.class)
public class HQLValidatingProcessor extends AbstractProcessor {

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (TypeElement annotation : annotations) {
            for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                if (element instanceof PackageElement) {
                    for (Element ee : element.getEnclosedElements()) {
                        checkHQL(ee);
                    }
                } else {
                    checkHQL(element);
                }
            }
        }
        return true;
    }

    private static void setHandler(Object object, ParseErrorHandler handler) {
        try {
            Field field = object.getClass().getDeclaredField("parseErrorHandler");
            field.setAccessible(true);
            field.set(object, handler);
        }
        catch (Exception e){
            e.printStackTrace();
        }
    }

    private void checkHQL(Element element) {
        Elements elementUtils = processingEnv.getElementUtils();
        if (elementUtils instanceof JavacElements) {
            JCTree tree = ((JavacElements) elementUtils).getTree(element);
            if (tree != null) {
                tree.accept(new TreeScanner() {

                    @Override
                    public void visitApply(JCTree.JCMethodInvocation jcMethodInvocation) {
                        Name name = getMethodName(jcMethodInvocation.getMethodSelect());
                        if (name != null && name.toString().equals("createQuery")) {
                            super.visitApply(jcMethodInvocation);
                        }
                    }

                    @Override
                    public void visitLiteral(JCTree.JCLiteral jcLiteral) {
                        if (jcLiteral.value instanceof String) {
                            String hql = (String) jcLiteral.value;

                            ParseErrorHandler handler =
                                    new JavacErrorReporter(jcLiteral, element, processingEnv);

                            //first parse with a special parser that correctly
                            //reports column numbers for syntax errors
                            try {
                                HqlParser parser = new HqlParser(hql){
                                    @Override
                                    public void reportError(String s) {
                                        handler.reportError(s);
                                    }
                                    @Override
                                    public void reportError(RecognitionException e) {
                                        handler.reportError(e);
                                    }
                                    @Override
                                    public void reportWarning(String s) {
                                        handler.reportWarning(s);
                                    }
                                };
                                parser.statement();

                                if (handler.getErrorCount()==0) {
                                    //now reparse and then transform with Hibernate's
                                    //parser and transformer, that don't correctly
                                    //report column numbers, but can do more stuff
                                    org.hibernate.hql.internal.ast.HqlParser parser2 =
                                            org.hibernate.hql.internal.ast.HqlParser.getInstance(hql);
                                    parser2.statement();

                                    SessionFactoryImplementor factory =
                                            new JavacSessionFactory(processingEnv);

                                    QueryTranslatorImpl queryTranslator =
                                            new QueryTranslatorImpl(null, hql, emptyMap(), factory);
                                    HqlSqlWalker walker = new HqlSqlWalker(queryTranslator, factory, parser2, emptyMap(), null) {
                                        @Override
                                        public void reportError(String s) {
                                            handler.reportError(s);
                                        }
                                        @Override
                                        public void reportError(RecognitionException e) {
                                            handler.reportError(e);
                                        }
                                        @Override
                                        public void reportWarning(String s) {
                                            handler.reportWarning(s);
                                        }
                                    };
                                    try {
                                        walker.statement(parser2.getAST());
                                    }
                                    catch (Exception e) {
                                        handler.reportError(e.getMessage());
                                    }

                                    //don't use this much simpler implementation
                                    //because it does too much stuff (generates SQL)
                                    //  queryTranslator.compile(null, false);
                                }

                            }
                            catch (Exception e) {
                                e.printStackTrace();
                            }

                        }
                    }

                });
            }
        }
    }

    static Symbol lookup(Names names, Symbol p, String name) {
        return p.members().lookup(names.fromString(name)).sym;
    }

    private static Name getMethodName(ExpressionTree select) {
        if (select instanceof MemberSelectTree) {
            MemberSelectTree ref = (MemberSelectTree) select;
            return ref.getIdentifier();
        } else if (select instanceof IdentifierTree) {
            IdentifierTree ref = (IdentifierTree) select;
            return ref.getName();
        } else {
            return null;
        }
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

}