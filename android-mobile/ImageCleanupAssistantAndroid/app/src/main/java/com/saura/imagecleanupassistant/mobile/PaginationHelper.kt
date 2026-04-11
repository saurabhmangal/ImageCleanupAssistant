package com.saura.imagecleanupassistant.mobile

import androidx.compose.runtime.Stable

/**
 * Helper for handling pagination of large datasets.
 * Enables smooth scrolling through thousands of images without loading all into memory.
 */
@Stable
data class PaginationState<T>(
    val items: List<T> = emptyList(),
    val pageSize: Int = 50,
    val currentPage: Int = 0,
    val totalItems: Int = 0,
    val isLoading: Boolean = false,
    val hasError: Boolean = false,
    val error: String? = null
) {
    val totalPages: Int = (totalItems + pageSize - 1) / pageSize
    val hasNextPage: Boolean = currentPage < totalPages - 1
    val hasPreviousPage: Boolean = currentPage > 0
    val displayItems: List<T> = items.take(pageSize * (currentPage + 1))
    val startIndex: Int = currentPage * pageSize
    val endIndex: Int = minOf(startIndex + pageSize, totalItems)
}

/**
 * Utility functions for pagination operations.
 */
object PaginationHelper {
    
    /**
     * Create paginated chunks from a list.
     */
    fun <T> paginate(
        items: List<T>,
        pageSize: Int = 50
    ): List<List<T>> {
        return items.chunked(pageSize)
    }
    
    /**
     * Get a specific page of items.
     */
    fun <T> getPage(
        items: List<T>,
        pageNumber: Int,
        pageSize: Int = 50
    ): List<T> {
        val startIndex = pageNumber * pageSize
        val endIndex = minOf(startIndex + pageSize, items.size)
        return if (startIndex < items.size) {
            items.subList(startIndex, endIndex)
        } else {
            emptyList()
        }
    }
    
    /**
     * Calculate if user has scrolled far enough to load next page.
     * Returns true when ~80% through current page.
     */
    fun shouldLoadNextPage(
        visibleItemsCount: Int,
        totalVisibleItems: Int,
        threshold: Float = 0.8f
    ): Boolean {
        return visibleItemsCount > (totalVisibleItems * threshold)
    }
    
    /**
     * Create pagination state with metadata.
     */
    fun <T> createState(
        items: List<T>,
        totalItems: Int,
        pageSize: Int = 50,
        currentPage: Int = 0,
        isLoading: Boolean = false
    ): PaginationState<T> {
        return PaginationState(
            items = items,
            pageSize = pageSize,
            currentPage = currentPage,
            totalItems = totalItems,
            isLoading = isLoading
        )
    }
}

/**
 * Loading states for better UX during pagination.
 */
sealed interface LoadingState {
    object Idle : LoadingState
    object Loading : LoadingState
    object LoadingMore : LoadingState
    data class Error(val message: String) : LoadingState
    object Complete : LoadingState
}
