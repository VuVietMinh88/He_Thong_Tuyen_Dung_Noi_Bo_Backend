package vn.ttcs.recruitment.department;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.common.ApiException;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The department side of handing over the work of an account (locking an account, feedback item 10): gives the
 * departments the account manages directly to other managers, with the locks and the manager rule of DepartmentService.
 * A department is held by the account when DepartmentRepository.findManagedBy lists it, the list the lock dialog
 * shows: the account is its manager, and it is active or still has open requisitions of its own. An inactive parent
 * whose only open work is below it is not held; PUT /departments/{id} still moves its manager.
 * <p>
 * Moving a manager moves the scope decided by departments.manager_user_id (findManagedDepartmentIds), the department
 * and every department below it, to the new manager. It grants no role or permission, so the new manager uses that
 * scope only with what it already has: it reads and writes those requisitions with a SCOPED permission, and decides
 * their HIRING_MANAGER and RECRUITER approval levels (ApproverRule), also of rounds already waiting, only when it holds
 * the role the level names. Any role may manage, as in DepartmentService; a manager without that role leaves those
 * levels to the holders of the role who manage a department above. Manager changes have no history, like
 * PUT /departments/{id}.
 * <p>
 * Both methods run in the caller's transaction (MANDATORY), which has already locked the accounts of the caller, of
 * the account handed over and of every recipient (in id order), then the caller's session.
 */
@Component
public class DepartmentManagerHandover {
    // The code and message of RequisitionHandover, so the dialog handles every stale item the same way.
    private static final String NOT_HELD =
            "Công việc này không còn thuộc tài khoản cần bàn giao. Vui lòng tải lại danh sách.";

    private final DepartmentRepository departments;

    public DepartmentManagerHandover(DepartmentRepository departments) {
        this.departments = departments;
    }

    // One department to hand over and its new manager. field is the request field of this item, such as
    // "departments[0]": an error about the item names field.departmentId or field.toUserId in fieldErrors.
    public record Transfer(String field, UUID departmentId, UUID toUserId) { }

    /**
     * Takes the tree lock of every department write (DepartmentService), right after the session and before any
     * requisition or department row, so a handover and a POST, PUT or DELETE of /departments run one after the other.
     * It may wait: the caller checks itself again before it writes anything.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockTree() {
        departments.acquireTreeWriteLock();
    }

    /**
     * Gives each department to its new manager, all or nothing: one failing item rolls the caller's transaction back
     * with every change made before it. Call it after lockTree and after the requisition rows of the same handover.
     * <p>
     * Locks the department rows in id order (lockForWrite, like PUT /departments/{id}), after the requisitions: the
     * order of requisition writes and approval decisions, which lock the requisition and then read its department FOR
     * SHARE. A row may wait for such a write, or for a change of the department's approval flow, so callerCheck runs
     * once all of them are held, and throws to refuse the caller. Then each item, in the order of the request: the
     * department is held by fromUserId (otherwise 409 HANDOVER_ITEM_NOT_HELD), and the recipient may sign in (400
     * INVALID_DEPARTMENT_MANAGER, the rule of DepartmentService for a new manager; any role).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void transfer(UUID fromUserId, List<Transfer> transfers, Map<UUID, Account> lockedAccounts,
                         Runnable callerCheck) {
        // Text order is PostgreSQL's uuid order, which UUID.compareTo (signed) is not.
        transfers.stream().map(Transfer::departmentId).distinct().sorted(Comparator.comparing(UUID::toString))
                .forEach(departments::lockForWrite);
        callerCheck.run();
        Set<UUID> held = departments.findManagedBy(fromUserId).stream()
                .map(DepartmentRepository.ManagedDepartment::departmentId).collect(Collectors.toSet());
        for (Transfer item : transfers) {
            if (!held.contains(item.departmentId())) {
                throw new ApiException(HttpStatus.CONFLICT, "HANDOVER_ITEM_NOT_HELD", NOT_HELD,
                        Map.of(item.field() + ".departmentId", NOT_HELD));
            }
            // Read while locked, so an admin lock committed while the handover waited is seen here.
            Account recipient = lockedAccounts.get(item.toUserId());
            if (recipient == null || !recipient.isAccessAllowed()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DEPARTMENT_MANAGER",
                        DepartmentService.INVALID_MANAGER_MESSAGE,
                        Map.of(item.field() + ".toUserId", DepartmentService.INVALID_MANAGER_MESSAGE));
            }
            departments.updateManager(item.departmentId(), item.toUserId());
        }
    }
}
