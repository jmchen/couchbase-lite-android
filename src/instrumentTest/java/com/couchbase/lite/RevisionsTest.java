package com.couchbase.lite;

import com.couchbase.lite.internal.RevisionInternal;
import com.couchbase.lite.util.Log;

import junit.framework.Assert;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class RevisionsTest extends LiteTestCase {

    public void testParseRevID() {
        int num;
        String suffix;

        num = Database.parseRevIDNumber("1-utiopturoewpt");
        Assert.assertEquals(1, num);
        suffix = Database.parseRevIDSuffix("1-utiopturoewpt");
        Assert.assertEquals("utiopturoewpt", suffix);

        num = Database.parseRevIDNumber("321-fdjfdsj-e");
        Assert.assertEquals(321, num);
        suffix = Database.parseRevIDSuffix("321-fdjfdsj-e");
        Assert.assertEquals("fdjfdsj-e", suffix);

        num = Database.parseRevIDNumber("0-fdjfdsj-e");
        suffix = Database.parseRevIDSuffix("0-fdjfdsj-e");
        Assert.assertTrue(num == 0 || (suffix.length() == 0));
        num = Database.parseRevIDNumber("-4-fdjfdsj-e");
        suffix = Database.parseRevIDSuffix("-4-fdjfdsj-e");
        Assert.assertTrue(num < 0 || (suffix.length() == 0));
        num = Database.parseRevIDNumber("5_fdjfdsj-e");
        suffix = Database.parseRevIDSuffix("5_fdjfdsj-e");
        Assert.assertTrue(num < 0 || (suffix.length() == 0));
        num = Database.parseRevIDNumber(" 5-fdjfdsj-e");
        suffix = Database.parseRevIDSuffix(" 5-fdjfdsj-e");
        Assert.assertTrue(num < 0 || (suffix.length() == 0));
        num = Database.parseRevIDNumber("7 -foo");
        suffix = Database.parseRevIDSuffix("7 -foo");
        Assert.assertTrue(num < 0 || (suffix.length() == 0));
        num = Database.parseRevIDNumber("7-");
        suffix = Database.parseRevIDSuffix("7-");
        Assert.assertTrue(num < 0 || (suffix.length() == 0));
        num = Database.parseRevIDNumber("7");
        suffix = Database.parseRevIDSuffix("7");
        Assert.assertTrue(num < 0 || (suffix.length() == 0));
        num = Database.parseRevIDNumber("eiuwtiu");
        suffix = Database.parseRevIDSuffix("eiuwtiu");
        Assert.assertTrue(num < 0 || (suffix.length() == 0));
        num = Database.parseRevIDNumber("");
        suffix = Database.parseRevIDSuffix("");
        Assert.assertTrue(num < 0 || (suffix.length() == 0));
    }

    public void testCBLCompareRevIDs() {

        // Single Digit
        Assert.assertTrue(RevisionInternal.CBLCollateRevIDs("1-foo", "1-foo") == 0);
        Assert.assertTrue(RevisionInternal.CBLCollateRevIDs("2-bar", "1-foo") > 0);
        Assert.assertTrue(RevisionInternal.CBLCollateRevIDs("1-foo", "2-bar") < 0);

        // Multi-digit:
        Assert.assertTrue(RevisionInternal.CBLCollateRevIDs("123-bar", "456-foo") < 0);
        Assert.assertTrue(RevisionInternal.CBLCollateRevIDs("456-foo", "123-bar") > 0);
        Assert.assertTrue(RevisionInternal.CBLCollateRevIDs("456-foo", "456-foo") == 0);
        Assert.assertTrue(RevisionInternal.CBLCollateRevIDs("456-foo", "456-foofoo") < 0);

        // Different numbers of digits:
        Assert.assertTrue(RevisionInternal.CBLCollateRevIDs("89-foo", "123-bar") < 0);
        Assert.assertTrue(RevisionInternal.CBLCollateRevIDs("123-bar", "89-foo") > 0);

        // Edge cases:
        Assert.assertTrue(RevisionInternal.CBLCollateRevIDs("123-", "89-") > 0);
        Assert.assertTrue(RevisionInternal.CBLCollateRevIDs("123-a", "123-a") == 0);

        // Invalid rev IDs:
        Assert.assertTrue(RevisionInternal.CBLCollateRevIDs("-a", "-b") < 0);
        Assert.assertTrue(RevisionInternal.CBLCollateRevIDs("-", "-") == 0);
        Assert.assertTrue(RevisionInternal.CBLCollateRevIDs("", "") == 0);
        Assert.assertTrue(RevisionInternal.CBLCollateRevIDs("", "-b") < 0);
        Assert.assertTrue(RevisionInternal.CBLCollateRevIDs("bogus", "yo") < 0);
        Assert.assertTrue(RevisionInternal.CBLCollateRevIDs("bogus-x", "yo-y") < 0);

    }

    public void testMakeRevisionHistoryDict() {
        List<RevisionInternal> revs = new ArrayList<RevisionInternal>();
        revs.add(mkrev("4-jkl"));
        revs.add(mkrev("3-ghi"));
        revs.add(mkrev("2-def"));

        List<String> expectedSuffixes = new ArrayList<String>();
        expectedSuffixes.add("jkl");
        expectedSuffixes.add("ghi");
        expectedSuffixes.add("def");
        Map<String,Object> expectedHistoryDict = new HashMap<String,Object>();
        expectedHistoryDict.put("start", 4);
        expectedHistoryDict.put("ids", expectedSuffixes);

        Map<String,Object> historyDict = Database.makeRevisionHistoryDict(revs);
        Assert.assertEquals(expectedHistoryDict, historyDict);


        revs = new ArrayList<RevisionInternal>();
        revs.add(mkrev("4-jkl"));
        revs.add(mkrev("2-def"));

        expectedSuffixes = new ArrayList<String>();
        expectedSuffixes.add("4-jkl");
        expectedSuffixes.add("2-def");
        expectedHistoryDict = new HashMap<String,Object>();
        expectedHistoryDict.put("ids", expectedSuffixes);

        historyDict = Database.makeRevisionHistoryDict(revs);
        Assert.assertEquals(expectedHistoryDict, historyDict);


        revs = new ArrayList<RevisionInternal>();
        revs.add(mkrev("12345"));
        revs.add(mkrev("6789"));

        expectedSuffixes = new ArrayList<String>();
        expectedSuffixes.add("12345");
        expectedSuffixes.add("6789");
        expectedHistoryDict = new HashMap<String,Object>();
        expectedHistoryDict.put("ids", expectedSuffixes);

        historyDict = Database.makeRevisionHistoryDict(revs);
        Assert.assertEquals(expectedHistoryDict, historyDict);

    }

    /**
     * https://github.com/couchbase/couchbase-lite-java-core/issues/106
     */
    public void testResolveConflict() throws Exception {

        // Create a conflict on purpose
        Document doc = database.createDocument();
        SavedRevision rev1 = doc.createRevision().save();
        SavedRevision rev2a = rev1.createRevision().save();
        SavedRevision rev2b = rev1.createRevision().save(true);

        SavedRevision winningRev = null;
        SavedRevision losingRev = null;
        if (doc.getCurrentRevisionId().equals(rev2a.getId())) {
            winningRev = rev2a;
            losingRev = rev2b;
        } else {
            winningRev = rev2b;
            losingRev = rev2a;
        }

        assertEquals(2,doc.getConflictingRevisions().size());
        assertEquals(2, doc.getLeafRevisions().size());

        // let's manually choose the losing rev as the winner.  First, delete winner, which will
        // cause losing rev to be the current revision.
        SavedRevision deleteRevision = winningRev.deleteDocument();

        List<SavedRevision> conflictingRevisions = doc.getConflictingRevisions();
        assertEquals(1, conflictingRevisions.size());
        assertEquals(2, doc.getLeafRevisions().size());

        assertEquals(3, deleteRevision.getGeneration());
        assertEquals(losingRev.getId(), doc.getCurrentRevision().getId());

        // Finally create a new revision rev3 based on losing rev
        SavedRevision rev3 = losingRev.createRevision().save(true);

        assertEquals(rev3.getId(), doc.getCurrentRevisionId());

        List<SavedRevision> conflictingRevisions1 = doc.getConflictingRevisions();
        assertEquals(1, conflictingRevisions1.size());
        assertEquals(2, doc.getLeafRevisions().size());

    }

    public void testCorrectWinningRevisionTiebreaker() throws Exception {

        // Create a conflict on purpose
        Document doc = database.createDocument();
        SavedRevision rev1 = doc.createRevision().save();
        SavedRevision rev2a = rev1.createRevision().save();
        SavedRevision rev2b = rev1.createRevision().save(true);

        // the tiebreaker will happen based on which rev hash has lexicographically higher sort order
        SavedRevision expectedWinner = null;
        if (rev2a.getId().compareTo(rev2b.getId()) > 0) {
            expectedWinner = rev2a;
        } else if (rev2a.getId().compareTo(rev2b.getId()) < 0) {
            expectedWinner = rev2b;
        }

        RevisionInternal revFound = database.getDocumentWithIDAndRev(doc.getId(), null, EnumSet.noneOf(Database.TDContentOptions.class));
        assertEquals(expectedWinner.getId(), revFound.getRevId());

    }

    public void testCorrectWinningRevisionLongerBranch() throws Exception {

        // Create a conflict on purpose
        Document doc = database.createDocument();
        SavedRevision rev1 = doc.createRevision().save();
        SavedRevision rev2a = rev1.createRevision().save();
        SavedRevision rev2b = rev1.createRevision().save(true);
        SavedRevision rev3b = rev2b.createRevision().save(true);

        // rev3b should be picked as the winner since it has a longer branch
        SavedRevision expectedWinner = rev3b;

        RevisionInternal revFound = database.getDocumentWithIDAndRev(doc.getId(), null, EnumSet.noneOf(Database.TDContentOptions.class));
        assertEquals(expectedWinner.getId(), revFound.getRevId());

    }

    /**
     * https://github.com/couchbase/couchbase-lite-java-core/issues/135
     */
    public void testCorrectWinningRevisionHighRevisionNumber() throws Exception {

        // Create a conflict on purpose
        Document doc = database.createDocument();
        SavedRevision rev1 = doc.createRevision().save();
        SavedRevision rev2a = rev1.createRevision().save();
        SavedRevision rev2b = rev1.createRevision().save(true);
        SavedRevision rev3b = rev2b.createRevision().save(true);
        SavedRevision rev4b = rev3b.createRevision().save(true);
        SavedRevision rev5b = rev4b.createRevision().save(true);
        SavedRevision rev6b = rev5b.createRevision().save(true);
        SavedRevision rev7b = rev6b.createRevision().save(true);
        SavedRevision rev8b = rev7b.createRevision().save(true);
        SavedRevision rev9b = rev8b.createRevision().save(true);
        SavedRevision rev10b = rev9b.createRevision().save(true);

        RevisionInternal revFound = database.getDocumentWithIDAndRev(doc.getId(), null, EnumSet.noneOf(Database.TDContentOptions.class));
        assertEquals(rev10b.getId(), revFound.getRevId());

    }

    public void testDocumentChangeListener() throws Exception {

        Document doc = database.createDocument();
        final CountDownLatch documentChanged = new CountDownLatch(1);
        doc.addChangeListener(new Document.ChangeListener() {
            @Override
            public void changed(Document.ChangeEvent event) {
                DocumentChange docChange = event.getChange();
                String msg = "New revision added: %s.  Conflict: %s";
                msg = String.format(msg, docChange.getAddedRevision(), docChange.isConflict());
                Log.d(TAG, msg);
                documentChanged.countDown();
            }
        });
        doc.createRevision().save();
        boolean success = documentChanged.await(30, TimeUnit.SECONDS);
        assertTrue(success);

    }

    private static RevisionInternal mkrev(String revID) {
        return new RevisionInternal("docid", revID, false, null);
    }

}
