package fi.italeino.aidos.engine.approval

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for ApprovalManager (RFC-0103: approval-based access control).
 */
class ApprovalManagerTest {

    @Test
    fun testFirstRequestReturnsApprovalStatusPending() {
        val manager = ApprovalManager()
        
        val status = manager.getApprovalStatus("com.example.app")
        
        assertEquals(ApprovalStatus.PENDING, status)
    }

    @Test
    fun testSecondRequestFromSameAppReturnsSamePendingStatus() {
        val manager = ApprovalManager()
        
        manager.getApprovalStatus("com.example.app")
        val secondStatus = manager.getApprovalStatus("com.example.app")
        
        assertEquals(ApprovalStatus.PENDING, secondStatus)
    }

    @Test
    fun testApproveAppChangesStatus() {
        val manager = ApprovalManager()
        
        manager.getApprovalStatus("com.example.app")
        manager.approveApp("com.example.app")
        val status = manager.getApprovalStatus("com.example.app")
        
        assertEquals(ApprovalStatus.APPROVED, status)
    }

    @Test
    fun testDenyAppChangesStatus() {
        val manager = ApprovalManager()
        
        manager.getApprovalStatus("com.example.app")
        manager.denyApp("com.example.app")
        val status = manager.getApprovalStatus("com.example.app")
        
        assertEquals(ApprovalStatus.DENIED, status)
    }

    @Test
    fun testGetPendingRequestsReturnsOnlyPending() {
        val manager = ApprovalManager()
        
        manager.getApprovalStatus("com.example.app1")
        manager.getApprovalStatus("com.example.app2")
        manager.approveApp("com.example.app1")
        
        val pending = manager.getPendingRequests()
        
        assertEquals(1, pending.size)
        assertEquals("com.example.app2", pending[0].packageName)
    }

    @Test
    fun testGetApprovedAppsReturnsOnlyApproved() {
        val manager = ApprovalManager()
        
        manager.getApprovalStatus("com.example.app1")
        manager.getApprovalStatus("com.example.app2")
        manager.approveApp("com.example.app1")
        manager.approveApp("com.example.app2")
        
        val approved = manager.getApprovedApps()
        
        assertEquals(2, approved.size)
    }

    @Test
    fun testRevokeAppRemovesFromApproved() {
        val manager = ApprovalManager()
        
        manager.getApprovalStatus("com.example.app")
        manager.approveApp("com.example.app")
        manager.revokeApp("com.example.app")
        
        val approved = manager.getApprovedApps()
        
        assertTrue(approved.isEmpty())
    }

    @Test
    fun testRecordRequestIncrementsCounter() {
        val manager = ApprovalManager()
        
        manager.recordRequest("com.example.app")
        manager.recordRequest("com.example.app")
        val count = manager.getRequestCount("com.example.app")
        
        assertEquals(2, count)
    }

    @Test
    fun testGetRequestCountReturnsZeroForUntracked() {
        val manager = ApprovalManager()
        
        val count = manager.getRequestCount("com.example.app")
        
        assertEquals(0, count)
    }

    @Test
    fun testMultipleAppsTrackedIndependently() {
        val manager = ApprovalManager()
        
        manager.getApprovalStatus("com.example.app1")
        manager.getApprovalStatus("com.example.app2")
        manager.approveApp("com.example.app1")
        
        val status1 = manager.getApprovalStatus("com.example.app1")
        val status2 = manager.getApprovalStatus("com.example.app2")
        
        assertEquals(ApprovalStatus.APPROVED, status1)
        assertEquals(ApprovalStatus.PENDING, status2)
    }

    @Test
    fun testRevokeAppsAlsoClearsRequestCount() {
        val manager = ApprovalManager()
        
        manager.getApprovalStatus("com.example.app")
        manager.recordRequest("com.example.app")
        manager.recordRequest("com.example.app")
        manager.revokeApp("com.example.app")
        
        val count = manager.getRequestCount("com.example.app")
        
        assertEquals(0, count)
    }
}
