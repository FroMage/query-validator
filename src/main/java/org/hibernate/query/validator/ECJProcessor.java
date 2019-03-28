package org.hibernate.query.validator;

import antlr.RecognitionException;
import org.eclipse.jdt.core.compiler.CategorizedProblem;
import org.eclipse.jdt.internal.compiler.ASTVisitor;
import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.Compiler;
import org.eclipse.jdt.internal.compiler.apt.dispatch.BaseProcessingEnvImpl;
import org.eclipse.jdt.internal.compiler.ast.*;
import org.eclipse.jdt.internal.compiler.lookup.Binding;
import org.eclipse.jdt.internal.compiler.lookup.BlockScope;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;
import org.eclipse.jdt.internal.compiler.problem.ProblemSeverities;
import org.hibernate.QueryException;
import org.hibernate.hql.internal.ast.ParseErrorHandler;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import java.util.Set;

import static org.eclipse.jdt.core.compiler.CharOperation.charToString;
import static org.eclipse.jdt.internal.compiler.util.Util.getLineNumber;
import static org.eclipse.jdt.internal.compiler.util.Util.searchColumnNumber;
import static org.hibernate.query.validator.ECJSessionFactory.hasAnnotation;
import static org.hibernate.query.validator.ECJSessionFactory.qualifiedName;
import static org.hibernate.query.validator.Validation.validate;

/**
 * Annotation processor that validates HQL and JPQL queries
 * for ECJ.
 *
 * @see CheckHQL
 */
//@SupportedAnnotationTypes(CHECK_HQL)
//@AutoService(Processor.class)
public class ECJProcessor extends AbstractProcessor {

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        Compiler compiler = ((BaseProcessingEnvImpl) processingEnv).getCompiler();
        if (!roundEnv.getRootElements().isEmpty()) {
            for (CompilationUnitDeclaration unit : compiler.unitsToProcess) {
                compiler.parser.getMethodBodies(unit);
                checkHQL(unit, compiler);
            }
        }
        return true;
    }

    private void checkHQL(CompilationUnitDeclaration unit, Compiler compiler) {
        for (TypeDeclaration type : unit.types) {
            if (isCheckable(type.binding, unit)) {
                type.traverse(new ASTVisitor() {
                    boolean inCreateQueryMethod = false;

                    @Override
                    public boolean visit(MessageSend messageSend, BlockScope scope) {
                        inCreateQueryMethod =
                                charToString(messageSend.selector)
                                        .equals("createQuery");
                        return true;
                    }

                    @Override
                    public void endVisit(MessageSend messageSend, BlockScope scope) {
                        inCreateQueryMethod = false;
                    }

                    @Override
                    public boolean visit(MemberValuePair pair, BlockScope scope) {
                        if (qualifiedName(pair.binding)
                                .equals("javax.persistence.NamedQuery.query")) {
                            inCreateQueryMethod = true;
                        }
                        return true;
                    }

                    @Override
                    public void endVisit(MemberValuePair pair, BlockScope scope) {
                        inCreateQueryMethod = false;
                    }

                    @Override
                    public void endVisit(StringLiteral stringLiteral, BlockScope scope) {
                        if (inCreateQueryMethod) {
                            String hql = charToString(stringLiteral.source());
                            ErrorReporter handler = new ErrorReporter(stringLiteral, unit, compiler);
                            validate(hql, handler, new ECJSessionFactory(handler, unit));
                        }

                    }
                }, unit.scope);
            }
        }
    }

    private static boolean isCheckable(TypeBinding type, CompilationUnitDeclaration unit) {
        Binding packInfo = unit.scope.getType("package-info".toCharArray());
        return hasAnnotation(packInfo, CheckHQL.class.getName())
            || hasAnnotation(type, CheckHQL.class.getName());
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    class ErrorReporter implements ParseErrorHandler {

        private StringLiteral literal;
        private CompilationUnitDeclaration unit;
        private Compiler compiler;

        ErrorReporter(StringLiteral literal,
                      CompilationUnitDeclaration unit,
                      Compiler compiler) {
            this.literal = literal;
            this.unit = unit;
            this.compiler = compiler;
        }

        @Override
        public int getErrorCount() {
            return 0;
        }

        @Override
        public void throwQueryException() throws QueryException {
        }

        private void report(int severity, String message, int offset) {
            CompilationResult result = unit.compilationResult();
            char[] fileName = result.fileName;
            int[] lineEnds = result.getLineSeparatorPositions();
            int startPosition = literal.sourceStart + offset + 1;
            int endPosition = literal.sourceEnd - 1; //search for the end of the token!
            int lineNumber = startPosition >= 0
                    ? getLineNumber(startPosition, lineEnds, 0, lineEnds.length - 1)
                    : 0;
            int columnNumber = startPosition >= 0
                    ? searchColumnNumber(lineEnds, lineNumber, startPosition)
                    : 0;

            CategorizedProblem problem =
                    compiler.problemReporter.problemFactory
                            .createProblem(fileName, 0,
                                    new String[]{message},
                                    new String[]{message},
                                    severity,
                                    startPosition, endPosition,
                                    lineNumber, columnNumber);
            compiler.problemReporter.record(problem, result, unit, true);
        }

        @Override
        public void reportError(RecognitionException e) {
            report(ProblemSeverities.Error, e.getMessage(), e.getColumn() - 1);
        }

        @Override
        public void reportError(String text) {
            report(ProblemSeverities.Error, text, 0);
        }

        @Override
        public void reportWarning(String text) {
            report(ProblemSeverities.Warning, text, 0);
        }

    }

}