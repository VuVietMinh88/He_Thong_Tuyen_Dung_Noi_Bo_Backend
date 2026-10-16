package vn.ttcs.recruitment.requisition;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// Task 256: who may decide a level, without a database.
class ApproverRuleTest {
    private static final Set<String> READ_WRITE = Set.of("REQUISITIONS_READ_SCOPED", "REQUISITIONS_WRITE_SCOPED");
    private static final Set<String> COMPANY_WIDE = Set.of("APPROVER", "HR_MANAGER", "ADMIN");
    private final UUID department = UUID.randomUUID();
    private final UUID parent = UUID.randomUUID();
    private final UUID creator = UUID.randomUUID();
    private final UUID submitter = UUID.randomUUID();

    @Test
    void aUserStepIsDecidedByTheAccountItNamesOnly() {
        var named = person(Set.of("APPROVER"), READ_WRITE, Set.of());
        var other = person(Set.of("APPROVER"), READ_WRITE, Set.of());
        var level = new ApprovalLevel(1, named.id(), null, false);
        var context = context(Set.of(named.id()), Set.of());

        assertThat(ApproverRule.admits(level, context, named)).isTrue();
        assertThat(ApproverRule.admits(level, context, other)).isFalse();
    }

    @Test
    void theDeciderMustBeAllowedInAndMayBothWriteAndReadRequisitions() {
        var level = role("HR_MANAGER");
        var context = context(Set.of(), Set.of());

        assertThat(ApproverRule.admits(level, context, person(Set.of("HR_MANAGER"), READ_WRITE, Set.of()))).isTrue();
        assertThat(ApproverRule.admits(level, context, locked(Set.of("HR_MANAGER")))).isFalse();
        assertThat(ApproverRule.admits(level, context,
                person(Set.of("HR_MANAGER"), Set.of("REQUISITIONS_READ_ALL"), Set.of()))).isFalse();
        assertThat(ApproverRule.admits(level, context,
                person(Set.of("HR_MANAGER"), Set.of("REQUISITIONS_WRITE_ALL"), Set.of()))).isFalse();
        assertThat(ApproverRule.admits(level, context,
                person(Set.of("HR_MANAGER"), Set.of("REQUISITIONS_READ_ALL", "REQUISITIONS_WRITE_ALL"), Set.of())))
                .isTrue();
    }

    @Test
    void theCreatorTheSubmitterAndWhoeverDecidedAnotherLevelOfTheRoundAreRefused() {
        var level = role("APPROVER");
        var anna = person(creator, Set.of("APPROVER"));
        var binh = person(submitter, Set.of("APPROVER"));
        var chi = person(Set.of("APPROVER"), READ_WRITE, Set.of());

        assertThat(ApproverRule.admits(level, context(Set.of(), Set.of()), anna)).isFalse();
        assertThat(ApproverRule.admits(level, context(Set.of(), Set.of()), binh)).isFalse();
        assertThat(ApproverRule.admits(level, context(Set.of(), Set.of()), chi)).isTrue();
        assertThat(ApproverRule.admits(level, context(Set.of(), Set.of(chi.id())), chi)).isFalse();
        // targets ignores separation of duties: the creator is still the person the level is routed to.
        assertThat(ApproverRule.targets(new ApprovalLevel(1, creator, null, false), context(Set.of(creator), Set.of()),
                anna)).isTrue();
    }

    @Test
    void companyWideRolesDecideForEveryDepartment() {
        for (String role : COMPANY_WIDE) {
            assertThat(ApproverRule.admits(role(role), context(Set.of(), Set.of()), person(Set.of(role), READ_WRITE,
                    Set.of()))).as(role).isTrue();
        }
    }

    @Test
    void departmentRolesDecideOnlyForTheDepartmentsTheHolderManages() {
        var level = role("HIRING_MANAGER");
        var context = context(Set.of(), Set.of());

        assertThat(ApproverRule.admits(level, context, person(Set.of("HIRING_MANAGER"), READ_WRITE,
                Set.of(department)))).isTrue();
        // The manager of a parent department manages its children too (findManagedDepartmentIds).
        assertThat(ApproverRule.admits(level, context, person(Set.of("HIRING_MANAGER"), READ_WRITE,
                Set.of(parent, department)))).isTrue();
        assertThat(ApproverRule.admits(level, context, person(Set.of("HIRING_MANAGER"), READ_WRITE,
                Set.of(UUID.randomUUID())))).isFalse();
        assertThat(ApproverRule.admits(role("RECRUITER"), context, person(Set.of("RECRUITER"), READ_WRITE,
                Set.of()))).isFalse();
        // Holding another role does not help.
        assertThat(ApproverRule.admits(level, context, person(Set.of("APPROVER"), READ_WRITE, Set.of(department))))
                .isFalse();
    }

    @Test
    void aPersonNamedByAUserStepOfTheChainCannotAlsoTakeOneOfItsRoleSteps() {
        var named = person(Set.of("APPROVER"), READ_WRITE, Set.of());

        assertThat(ApproverRule.admits(role("APPROVER"), context(Set.of(named.id()), Set.of()), named)).isFalse();
        assertThat(ApproverRule.targets(role("APPROVER"), context(Set.of(named.id()), Set.of()), named)).isFalse();
    }

    @Test
    void managedDepartmentsAreOnlyReadForADepartmentBoundRoleStep() {
        var reads = new AtomicInteger();
        var candidate = new ApproverCandidate(UUID.randomUUID(), "HR", true, Set.of("HR_MANAGER", "HIRING_MANAGER"),
                READ_WRITE, () -> {
                    reads.incrementAndGet();
                    return Set.of(department);
                });

        assertThat(ApproverRule.admits(role("HR_MANAGER"), context(Set.of(), Set.of()), candidate)).isTrue();
        assertThat(reads).hasValue(0);
        assertThat(ApproverRule.admits(role("HIRING_MANAGER"), context(Set.of(), Set.of()), candidate)).isTrue();
        assertThat(ApproverRule.admits(role("HIRING_MANAGER"), context(Set.of(), Set.of()), candidate)).isTrue();
        assertThat(reads).hasValue(1);
    }

    @Test
    void everyLevelNeedsItsOwnPerson() {
        UUID anna = UUID.randomUUID();
        UUID binh = UUID.randomUUID();

        assertThat(ApprovalChainSelector.hasDistinctApprovers(List.of(List.of(anna)))).isTrue();
        assertThat(ApprovalChainSelector.hasDistinctApprovers(List.of(List.of(anna), List.of(anna)))).isFalse();
        // Anna can take level 2 only if Binh takes level 1: the matching moves her.
        assertThat(ApprovalChainSelector.hasDistinctApprovers(List.of(List.of(anna, binh), List.of(anna)))).isTrue();
        assertThat(ApprovalChainSelector.hasDistinctApprovers(List.of(List.of(anna, binh), List.of(anna, binh),
                List.of(binh)))).isFalse();
        assertThat(ApprovalChainSelector.hasDistinctApprovers(List.of())).isTrue();
    }

    private ApprovalContext context(Set<UUID> named, Set<UUID> decided) {
        return new ApprovalContext(department, creator, submitter, named, decided, COMPANY_WIDE);
    }

    private static ApprovalLevel role(String role) {
        return new ApprovalLevel(1, null, role, false);
    }

    private static ApproverCandidate person(Set<String> roles, Set<String> permissions, Set<UUID> managed) {
        return new ApproverCandidate(UUID.randomUUID(), "Người duyệt", true, roles, permissions,
                () -> new HashSet<>(managed));
    }

    private static ApproverCandidate person(UUID id, Set<String> roles) {
        return new ApproverCandidate(id, "Người duyệt", true, roles, READ_WRITE, Set::of);
    }

    private static ApproverCandidate locked(Set<String> roles) {
        return new ApproverCandidate(UUID.randomUUID(), "Bị khóa", false, roles, READ_WRITE, Set::of);
    }
}
