package vn.ttcs.recruitment.candidatemerge;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static vn.ttcs.recruitment.candidatemerge.CandidateMergeSnapshot.*;

class CandidateMergeCommandTest {
    private final UUID left=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID right=UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
    private final UUID actor=UUID.randomUUID(), key=UUID.randomUUID();
    private CandidateMergeSnapshot snapshot() {
        return new CandidateMergeSnapshot(List.of(new Group(left,1,left,null),new Group(right,1,right,null)),
                List.of(new Member(left,left,0),new Member(right,right,0)));
    }
    @Test void fingerprintIsIndependentOfRowOrderingButIncludesAllSourceVersions() {
        var before=snapshot();
        var reversed=new CandidateMergeSnapshot(before.groups().reversed(),before.members().reversed());
        assertThat(reversed.fingerprint()).isEqualTo(before.fingerprint());
        var changed=new CandidateMergeSnapshot(before.groups(),List.of(new Member(left,left,1),new Member(right,right,0)));
        assertThat(changed.fingerprint()).isNotEqualTo(before.fingerprint());
        for (Group changedGroup : List.of(new Group(left,2,left,null),new Group(left,1,null,null),new Group(left,1,left,key))) {
            assertThat(new CandidateMergeSnapshot(List.of(changedGroup,before.groups().getLast()),before.members()).fingerprint())
                    .isNotEqualTo(before.fingerprint());
        }
    }
    @Test void canonicalGroupOrderMatchesPostgresIncludingHighBitUuid() {
        var a=new CandidateMergeCommand(actor,key,left,right,left,right,snapshot().fingerprint());
        var b=new CandidateMergeCommand(actor,key,right,left,left,right,snapshot().fingerprint());
        assertThat(a).isEqualTo(b); assertThat(a.leftGroupId()).isEqualTo(left);
        assertThat(a.payloadHash()).isEqualTo(b.payloadHash());
    }
    @Test void payloadHashIncludesRepresentativeTargetAndPreview() {
        var a=new CandidateMergeCommand(actor,key,left,right,left,left,snapshot().fingerprint());
        assertThat(a.payloadHash()).isNotEqualTo(new CandidateMergeCommand(actor,key,left,right,left,right,snapshot().fingerprint()).payloadHash());
        assertThat(a.payloadHash()).isNotEqualTo(new CandidateMergeCommand(actor,key,left,right,right,left,snapshot().fingerprint()).payloadHash());
        assertThat(a.payloadHash()).isNotEqualTo(new CandidateMergeCommand(actor,key,left,right,left,left,"0".repeat(64)).payloadHash());
    }
    @Test void rejectsMalformedCommandsWithoutIncludingInputInException() {
        assertThatThrownBy(()->new CandidateMergeCommand(actor,key,left,left,left,left,"private@email.test"))
                .isInstanceOf(MergeAuditException.class).hasMessageNotContaining("private@email.test");
        assertThatThrownBy(()->new CandidateMergeCommand(actor,key,left,right,actor,left,snapshot().fingerprint())).isInstanceOf(MergeAuditException.class);
        assertThatThrownBy(()->new CandidateMergeCommand(null,key,left,right,left,left,snapshot().fingerprint())).isInstanceOf(MergeAuditException.class);
        assertThatThrownBy(()->new CandidateMergeCommand(actor,key,left,right,left,left,"bad")).isInstanceOf(MergeAuditException.class);
    }
    @Test void snapshotsAreImmutableAndDiagnosticsRedacted() {
        var before=snapshot();
        assertThatThrownBy(()->before.members().clear()).isInstanceOf(UnsupportedOperationException.class);
        var command=new CandidateMergeCommand(actor,key,left,right,left,left,before.fingerprint());
        assertThat(command.toString()).doesNotContain(actor.toString(),key.toString());
        assertThat(before.toString()).doesNotContain(left.toString(),right.toString());
    }
}
