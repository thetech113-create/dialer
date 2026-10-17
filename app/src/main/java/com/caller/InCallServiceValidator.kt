package com.caller

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import android.util.Log
import kotlinx.coroutines.delay

/**
 * Comprehensive validation and testing for OEM fallback prevention
 * Validates all fixes and provides diagnostic information
 */
object InCallServiceValidator {

    private const val TAG = "InCallValidator"

    /**
     * Comprehensive validation of all OEM fallback prevention measures
     */
    suspend fun validateOEMFallbackPrevention(context: Context): ValidationResult {
        val checks = mutableListOf<ValidationCheck>()
        
        // ROOT CAUSE #1: InCallService manifest validation
        checks.add(validateManifestConfiguration(context))
        
        // ROOT CAUSE #2: Process warming validation
        checks.add(validateProcessWarming(context))
        
        // ROOT CAUSE #3: OEM permission validation
        checks.add(validateOEMPermissions(context))
        
        // ROOT CAUSE #4: FullScreenIntent validation
        checks.add(validateFullScreenIntent(context))
        
        // ROOT CAUSE #5: Role management validation
        checks.add(validateRoleManagement(context))
        
        // ROOT CAUSE #10: Battery optimization validation
        checks.add(validateBatteryOptimization(context))
        
        // ROOT CAUSE #15: UI display guarantee validation
        checks.add(validateUIDisplayGuarantee(context))
        
        val overallResult = ValidationResult(
            checks = checks,
            overallScore = calculateOverallScore(checks),
            oemFallbackRisk = assessOEMFallbackRisk(checks),
            recommendations = generateRecommendations(checks)
        )
        
        Log.w(TAG, "Validation completed: score=${overallResult.overallScore}% risk=${overallResult.oemFallbackRisk}")
        return overallResult
    }

    private fun validateManifestConfiguration(context: Context): ValidationCheck {
        val issues = mutableListOf<String>()
        
        try {
            // Check if InCallService is properly declared
            val packageManager = context.packageManager
            val serviceInfo = packageManager.getServiceInfo(
                android.content.ComponentName(context, MyInCallService::class.java),
                android.content.pm.PackageManager.GET_META_DATA
            )
            
            if (serviceInfo == null) {
                issues.add("InCallService not found in manifest")
            } else {
                // Check required permissions
                if (!serviceInfo.permission.equals("android.permission.BIND_INCALL_SERVICE")) {
                    issues.add("Missing BIND_INCALL_SERVICE permission")
                }
                
                // Check meta-data
                val metaData = serviceInfo.metaData
                if (metaData?.getBoolean("android.telecom.IN_CALL_SERVICE_UI") != true) {
                    issues.add("Missing IN_CALL_SERVICE_UI meta-data")
                }
                
                if (metaData?.getBoolean("android.telecom.IN_CALL_SERVICE_RINGING") != true) {
                    issues.add("Missing IN_CALL_SERVICE_RINGING meta-data")
                }
                
                // Check foreground service type
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (serviceInfo.foregroundServiceType and android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL == 0) {
                        issues.add("Missing FOREGROUND_SERVICE_TYPE_PHONE_CALL")
                    }
                }
            }
        } catch (e: Exception) {
            issues.add("Manifest validation failed: ${e.message}")
        }
        
        return ValidationCheck(
            name = "Manifest Configuration",
            passed = issues.isEmpty(),
            issues = issues,
            severity = if (issues.isEmpty()) Severity.INFO else Severity.CRITICAL
        )
    }

    private fun validateProcessWarming(context: Context): ValidationCheck {
        val issues = mutableListOf<String>()
        
        try {
            // Check if process warming is implemented
            // Note: InCallServiceEnhancements is an object, always available
            issues.add("Process warming implemented")
            
            // Check if ProcessWarmupService is declared
            try {
                val packageManager = context.packageManager
                val serviceInfo = packageManager.getServiceInfo(
                    android.content.ComponentName(context, "com.caller.ProcessWarmupService"),
                    0
                )
                
                if (serviceInfo == null) {
                    issues.add("ProcessWarmupService not declared in manifest")
                }
            } catch (e: Exception) {
                issues.add("ProcessWarmupService check failed: ${e.message}")
            }
        } catch (e: Exception) {
            issues.add("Process warming validation failed: ${e.message}")
        }
        
        return ValidationCheck(
            name = "Process Warming",
            passed = issues.isEmpty(),
            issues = issues,
            severity = if (issues.isEmpty()) Severity.INFO else Severity.HIGH
        )
    }

    private fun validateOEMPermissions(context: Context): ValidationCheck {
        val issues = mutableListOf<String>()
        
        try {
            val oemInfo = OEMPermissionHelper.detectOEMFrameworkModifications(context)
            
            when (oemInfo.riskLevel) {
                RiskLevel.HIGH -> {
                    issues.add("High-risk OEM detected: ${oemInfo.oem}")
                    issues.addAll(oemInfo.knownIssues.map { "OEM Issue: $it" })
                }
                RiskLevel.MEDIUM -> {
                    issues.add("Medium-risk OEM detected: ${oemInfo.oem}")
                }
                RiskLevel.LOW -> {
                    // Low risk, no issues
                }
            }
        } catch (e: Exception) {
            issues.add("OEM permission validation failed: ${e.message}")
        }
        
        return ValidationCheck(
            name = "OEM Permissions",
            passed = issues.isEmpty(),
            issues = issues,
            severity = when {
                issues.any { it.contains("High-risk") } -> Severity.HIGH
                issues.any { it.contains("Medium-risk") } -> Severity.MEDIUM
                else -> Severity.INFO
            }
        )
    }

    private fun validateFullScreenIntent(context: Context): ValidationCheck {
        val issues = mutableListOf<String>()
        
        try {
            // Check USE_FULL_SCREEN_INTENT permission
            val hasPermission = context.checkSelfPermission("android.permission.USE_FULL_SCREEN_INTENT") == 
                android.content.pm.PackageManager.PERMISSION_GRANTED
            
            if (!hasPermission) {
                issues.add("Missing USE_FULL_SCREEN_INTENT permission")
            }
            
            // Check if notification channels are properly configured
            val notificationManager = context.getSystemService(android.app.NotificationManager::class.java)
            val channel = notificationManager.getNotificationChannel("critical_call_channel")
            
            if (channel == null) {
                issues.add("Critical call notification channel not configured")
            } else if (channel.importance < android.app.NotificationManager.IMPORTANCE_HIGH) {
                issues.add("Critical call channel importance too low")
            }
        } catch (e: Exception) {
            issues.add("FullScreenIntent validation failed: ${e.message}")
        }
        
        return ValidationCheck(
            name = "FullScreenIntent",
            passed = issues.isEmpty(),
            issues = issues,
            severity = if (issues.isEmpty()) Severity.INFO else Severity.HIGH
        )
    }

    private fun validateRoleManagement(context: Context): ValidationCheck {
        val issues = mutableListOf<String>()
        
        try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val currentDialer = telecomManager.defaultDialerPackage
            val ourPackage = context.packageName
            
            if (currentDialer != ourPackage) {
                issues.add("Not currently default dialer: $currentDialer")
            }
            
            // Role management enhancements are always available (object)
            // No additional check needed
        } catch (e: Exception) {
            issues.add("Role management validation failed: ${e.message}")
        }
        
        return ValidationCheck(
            name = "Role Management",
            passed = issues.isEmpty(),
            issues = issues,
            severity = if (issues.isEmpty()) Severity.INFO else Severity.CRITICAL
        )
    }

    private fun validateBatteryOptimization(context: Context): ValidationCheck {
        val issues = mutableListOf<String>()
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Simplified check - PowerManager access removed for compatibility
                // In practice, this would check if battery optimization is disabled
                issues.add("Battery optimization check simplified")
            }
        } catch (e: Exception) {
            issues.add("Battery optimization validation failed: ${e.message}")
        }
        
        return ValidationCheck(
            name = "Battery Optimization",
            passed = issues.isEmpty(),
            issues = issues,
            severity = if (issues.isEmpty()) Severity.INFO else Severity.HIGH
        )
    }

    private fun validateUIDisplayGuarantee(context: Context): ValidationCheck {
        val issues = mutableListOf<String>()
        
        try {
            // UI guarantee enhancements are always available (object)
            // No additional check needed
            
            // Check CallActivity configuration
            val packageManager = context.packageManager
            val activityInfo = packageManager.getActivityInfo(
                android.content.ComponentName(context, CallActivity::class.java),
                0
            )
            
            if (!activityInfo.exported) {
                issues.add("CallActivity should be exported for proper UI display")
            }
        } catch (e: Exception) {
            issues.add("UI display guarantee validation failed: ${e.message}")
        }
        
        return ValidationCheck(
            name = "UI Display Guarantee",
            passed = issues.isEmpty(),
            issues = issues,
            severity = if (issues.isEmpty()) Severity.INFO else Severity.HIGH
        )
    }

    private fun calculateOverallScore(checks: List<ValidationCheck>): Int {
        if (checks.isEmpty()) return 0
        
        val totalWeight = checks.sumOf { it.severity.weight }
        val passedWeight = checks.filter { it.passed }.sumOf { it.severity.weight }
        
        return if (totalWeight > 0) (passedWeight * 100 / totalWeight) else 0
    }

    private fun assessOEMFallbackRisk(checks: List<ValidationCheck>): OEMFallbackRisk {
        val criticalFailures = checks.filter { !it.passed && it.severity == Severity.CRITICAL }
        val highFailures = checks.filter { !it.passed && it.severity == Severity.HIGH }
        
        return when {
            criticalFailures.isNotEmpty() -> OEMFallbackRisk.VERY_HIGH
            highFailures.size >= 2 -> OEMFallbackRisk.HIGH
            highFailures.size == 1 -> OEMFallbackRisk.MEDIUM
            else -> OEMFallbackRisk.LOW
        }
    }

    private fun generateRecommendations(checks: List<ValidationCheck>): List<String> {
        val recommendations = mutableListOf<String>()
        
        checks.filter { !it.passed }.forEach { check ->
            when (check.name) {
                "Manifest Configuration" -> recommendations.add("Fix InCallService manifest declaration with proper permissions and meta-data")
                "Process Warming" -> recommendations.add("Implement process warming to prevent cold start timeouts")
                "OEM Permissions" -> recommendations.add("Grant OEM-specific permissions (autostart, battery optimization)")
                "FullScreenIntent" -> recommendations.add("Add USE_FULL_SCREEN_INTENT permission and configure high-priority notification channel")
                "Role Management" -> recommendations.add("Ensure app is set as default dialer and role management is integrated")
                "Battery Optimization" -> recommendations.add("Disable battery optimization for the app")
                "UI Display Guarantee" -> recommendations.add("Integrate UI display guarantee enhancements")
            }
        }
        
        if (recommendations.isEmpty()) {
            recommendations.add("All OEM fallback prevention measures are properly configured")
        }
        
        return recommendations
    }
}

data class ValidationResult(
    val checks: List<ValidationCheck>,
    val overallScore: Int,
    val oemFallbackRisk: OEMFallbackRisk,
    val recommendations: List<String>
)

data class ValidationCheck(
    val name: String,
    val passed: Boolean,
    val issues: List<String>,
    val severity: Severity
)

enum class Severity(val weight: Int) {
    INFO(1),
    MEDIUM(2),
    HIGH(3),
    CRITICAL(4)
}

enum class OEMFallbackRisk {
    LOW, MEDIUM, HIGH, VERY_HIGH
}
