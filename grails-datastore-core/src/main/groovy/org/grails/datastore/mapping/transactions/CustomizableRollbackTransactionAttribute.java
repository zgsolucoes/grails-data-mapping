package org.grails.datastore.mapping.transactions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.NoRollbackRuleAttribute;
import org.springframework.transaction.interceptor.RollbackRuleAttribute;
import org.springframework.transaction.interceptor.RuleBasedTransactionAttribute;

import java.util.List;

/**
 * Extended version of {@link RuleBasedTransactionAttribute} that ensures all exception types are rolled back and allows inheritance of setRollbackOnly
 *
 * @author Graeme Rocher
 * @since 3.0
 */
public class CustomizableRollbackTransactionAttribute extends RuleBasedTransactionAttribute {

    private static final Logger log = LoggerFactory.getLogger(CustomizableRollbackTransactionAttribute.class);

    private static final long serialVersionUID = 1L;
    private boolean inheritRollbackOnly = true;
    private String connection;
    /**
     * ZeroGlosa (ZG-2497): what {@link #rollbackOn(Throwable)} answers when no rollback rule matches.
     * <ul>
     *     <li>{@code false}: Grails 2.3 / Spring rule — only {@link RuntimeException} and {@link Error} roll back; a checked
     *     exception propagates and the transaction commits (in a participating transaction the caller that catches it
     *     commits normally instead of getting an UnexpectedRollbackException).</li>
     *     <li>{@code true}: GORM 6 rule — any exception rolls back.</li>
     * </ul>
     * The no-argument constructor (the one the {@code @Transactional}/{@code @ReadOnly} AST transformation calls, so classes
     * already compiled in plugins get it too) uses the Grails 2.3 rule. The copy constructors used by
     * {@code withTransaction} ({@code GrailsTransactionTemplate(transactionManager, TransactionDefinition)}) keep the GORM 6
     * rule, as do {@code GrailsTransactionTemplate(transactionManager)} and the {@code TransactionService}.
     */
    private boolean rollbackOnAnyException = false;

    public CustomizableRollbackTransactionAttribute() {
        super();
    }

    public CustomizableRollbackTransactionAttribute(int propagationBehavior, List<RollbackRuleAttribute> rollbackRules) {
        super(propagationBehavior, rollbackRules);
    }

    public CustomizableRollbackTransactionAttribute(org.springframework.transaction.interceptor.TransactionAttribute other) {
        super();
        this.rollbackOnAnyException = true;
        setPropagationBehavior(other.getPropagationBehavior());
        setIsolationLevel(other.getIsolationLevel());
        setTimeout(other.getTimeout());
        setReadOnly(other.isReadOnly());
        setName(other.getName());
    }

    public CustomizableRollbackTransactionAttribute(TransactionDefinition other) {
        super();
        this.rollbackOnAnyException = true;
        setPropagationBehavior(other.getPropagationBehavior());
        setIsolationLevel(other.getIsolationLevel());
        setTimeout(other.getTimeout());
        setReadOnly(other.isReadOnly());
        setName(other.getName());
    }

    public CustomizableRollbackTransactionAttribute(CustomizableRollbackTransactionAttribute other) {
        this((RuleBasedTransactionAttribute)other);
    }

    public CustomizableRollbackTransactionAttribute(RuleBasedTransactionAttribute other) {
        this.rollbackOnAnyException = true;
        if(other instanceof CustomizableRollbackTransactionAttribute) {
            this.inheritRollbackOnly = ((CustomizableRollbackTransactionAttribute)other).inheritRollbackOnly;
            this.rollbackOnAnyException = ((CustomizableRollbackTransactionAttribute)other).rollbackOnAnyException;
        }
    }

    @Override
    public boolean rollbackOn(Throwable ex) {
        if (log.isTraceEnabled()) {
            log.trace("Applying rules to determine whether transaction should rollback on $ex");
        }

        RollbackRuleAttribute winner = null;
        int deepest = Integer.MAX_VALUE;

        List<RollbackRuleAttribute> rollbackRules = getRollbackRules();
        if (rollbackRules != null) {
            for (RollbackRuleAttribute rule : rollbackRules) {
                int depth = rule.getDepth(ex);
                if (depth >= 0 && depth < deepest) {
                    deepest = depth;
                    winner = rule;
                }
            }
        }

        if (log.isTraceEnabled()) {
            log.trace("Winning rollback rule is: $winner" );
        }

        // User superclass behavior (rollback on unchecked) if no rule matches.
        if (winner == null) {
            log.trace("No relevant rollback rule found: applying default rules");

            if (rollbackOnAnyException) {
                // GORM 6: always rollback regardless if it is a checked or unchecked exception since Groovy doesn't differentiate those
                return true;
            }
            // Grails 2.3 (ZG-2497): RuntimeException || Error
            return super.rollbackOn(ex);
        }

        return !(winner instanceof NoRollbackRuleAttribute);
    }

    /**
     * @return Whether any exception rolls back when no rollback rule matches (GORM 6) instead of only RuntimeException/Error (Grails 2.3)
     */
    public boolean isRollbackOnAnyException() {
        return rollbackOnAnyException;
    }

    public void setRollbackOnAnyException(boolean rollbackOnAnyException) {
        this.rollbackOnAnyException = rollbackOnAnyException;
    }
    public boolean isInheritRollbackOnly() {
        return inheritRollbackOnly;
    }

    public void setInheritRollbackOnly(boolean inheritRollbackOnly) {
        this.inheritRollbackOnly = inheritRollbackOnly;
    }

    /**
     * @return The connection being used
     */
    public String getConnection() {
        return connection;
    }

    public void setConnection(String connection) {
        this.connection = connection;
    }
}
