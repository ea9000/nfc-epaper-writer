package com.joshuatz.nfceinkwriter

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.BufferedReader
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL

class MainViewModel : ViewModel() {

    // LiveData for holding the list of extracted station identifiers
    private val _stationList = MutableLiveData<List<String>>()
    val stationList: LiveData<List<String>> = _stationList

    // LiveData for holding the content of the selected station's file
    private val _selectedStationText = MutableLiveData<String>()
    val selectedStationText: LiveData<String> = _selectedStationText

    // LiveData to indicate loading state for UI
    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    // LiveData for displaying error messages to the user
    private val _errorMessage = MutableLiveData<String>()
    val errorMessage: LiveData<String> = _errorMessage

    // LiveData to track if NFC write confirmation is pending
    private val _showNfcWriteConfirmation = MutableLiveData<Boolean>(false)
    val showNfcWriteConfirmation: LiveData<Boolean> = _showNfcWriteConfirmation

    // Stores the base URL provided by the user
    private var baseUrl: String = ""

    // Stores the raw list of file *paths extracted from the directory listing HTML*
    // These paths are relative to the directory being listed (e.g., "test.txt", "2025/").
    private var rawFilePaths: List<String> = emptyList()

    fun setBaseUrl(url: String) {
        baseUrl = url
        // Ensure base URL ends with a slash for consistent path concatenation
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/"
        }
        // Clear existing data when URL changes
        _stationList.value = emptyList()
        _selectedStationText.value = ""
        _errorMessage.value = ""
        Log.d("MainViewModel", "Base URL set to: $baseUrl")
    }

    fun setNfcWriteConfirmation(show: Boolean) {
        _showNfcWriteConfirmation.value = show
        Log.d("MainViewModel", "NFC write confirmation state: $show")
    }

    // Fetches file listings from the base URL (which is an HTML directory listing)
    // and extracts station identifiers by parsing the HTML links.
    fun fetchAndExtractStations() {
        if (baseUrl.isEmpty()) {
            _errorMessage.value = "Base URL is not set. Go to Settings."
            Log.w("MainViewModel", "fetchAndExtractStations: Base URL is empty.")
            return
        }

        _isLoading.value = true
        _errorMessage.value = "" // Clear previous errors
        rawFilePaths = emptyList() // Clear previous raw paths

        viewModelScope.launch(Dispatchers.IO) {
            var urlConnection: HttpURLConnection? = null
            try {
                Log.d("MainViewModel", "Attempting to fetch directory listing from: $baseUrl")
                val url = URL(baseUrl)
                urlConnection = url.openConnection() as HttpURLConnection
                urlConnection.requestMethod = "GET"
                urlConnection.connect()

                val responseCode = urlConnection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val htmlContent = BufferedReader(InputStreamReader(urlConnection.inputStream)).use { it.readText() }
                    Log.d("MainViewModel", "Successfully fetched HTML content. Parsing with Jsoup.")

                    val doc = Jsoup.parse(htmlContent)
                    // IIS Directory Browsing places links inside a <pre> tag.
                    // We select <a> tags within the <pre> block.
                    val links = doc.select("pre a[href]")

                    val extractedFilePaths = mutableListOf<String>()
                    val uniqueStations = mutableSetOf<String>()

                    for (link in links) {
                        val href = link.attr("href")
                        val linkText = link.text()

                        // Skip parent directory link ".." and current directory link "."
                        if (href == "../" || href == "./") {
                            continue
                        }

                        // Filter out non-relevant links (e.g., query strings, non-file links)
                        if (href.contains("?C=") || href.contains("?M=") || href.contains("?S=") || href.contains("?D=")) {
                             continue // Skip IIS sorting links
                        }

                        // Consider only actual files or directories that match your station pattern
                        // Assuming files are like "station 1 2025/01/02.txt" or "station 5.txt"
                        // Or directories like "station 1 2025/01/02/" that contain sub-files
                        
                        // Heuristic: If it looks like a file or a date-structured directory
                        // Example: test.txt, web.config, station 1 2025/01/02.txt, 2025/
                        if (linkText.endsWith(".txt", ignoreCase = true) || linkText.endsWith(".config", ignoreCase = true) || linkText.matches("\\d{4}/\\d{2}/\\d{1,2}/?".toRegex()) || linkText.matches(".*\\d{4}.*".toRegex())) {
                            extractedFilePaths.add(href) // Store the actual href for download

                            // Extract the station identifier from the linkText (e.g., "test" from "test.txt")
                            // This part needs to be very robust based on your naming convention for stations.
                            // If your files are named "station X YYYY/MM/DD.txt" and you want "station X"
                            val stationIdentifier = when {
                                // For files like "station name YYYY/MM/DD.txt" or "station name.txt"
                                linkText.contains(".txt", ignoreCase = true) -> linkText.substringBeforeLast(".txt", linkText).trim()
                                linkText.endsWith("/", ignoreCase = true) -> linkText.removeSuffix("/").trim() // For directories
                                else -> linkText.trim() // Fallback for other cases
                            }

                            // Further refine station identifier if it contains dates or other irrelevant info
                            val cleanedStation = stationIdentifier.split(" ").filter { !it.matches("\\d{4}|\\d{2}|\\d{1,2}".toRegex()) && !it.matches("[0-9]{1,2}:[0-9]{2}\\s[AP]M".toRegex()) }.joinToString(" ")
                                // Remove date patterns (YYYY, MM, DD) and time patterns from the station name.
                                .replace(Regex("\\s*\\d{4}/\\d{2}/\\d{1,2}\\s*"), "") // YYYY/MM/DD
                                .replace(Regex("\\s*\\d{4}-\\d{2}-\\d{1,2}\\s*"), "") // YYYY-MM-DD
                                .replace(Regex("\\s*\\d{1,2}:\\d{2}\\s*(AM|PM)\\s*"), "") // HH:MM AM/PM
                                .replace(Regex("\\s*\\d+\\s*KB|MB|GB\\s*"), "") // File sizes
                                .trim()


                            if (cleanedStation.isNotEmpty() && !cleanedStation.equals("web.config", ignoreCase = true)) {
                                uniqueStations.add(cleanedStation.take(20)) // Take first 20 chars as specified
                            }
                        }
                    }

                    rawFilePaths = extractedFilePaths // Store extracted relative paths

                    withContext(Dispatchers.Main) {
                        _stationList.value = uniqueStations.toList().sorted()
                        if (uniqueStations.isEmpty()) {
                            _errorMessage.value = "No relevant files found in directory listing. Check URL and file naming."
                            Log.w("MainViewModel", "No unique station identifiers extracted after HTML parsing.")
                        } else {
                            Log.i("MainViewModel", "Successfully extracted ${uniqueStations.size} unique stations: ${uniqueStations.toList().sorted()}")
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        _errorMessage.value = "Failed to fetch directory listing: HTTP $responseCode"
                        Log.e("MainViewModel", "HTTP error fetching directory listing: $responseCode from $baseUrl")
                    }
                }
            } catch (e: MalformedURLException) {
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "Invalid URL format: ${e.localizedMessage}"
                    Log.e("MainViewModel", "Malformed URL: $baseUrl", e)
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "Network error or cannot access URL: ${e.localizedMessage}"
                    Log.e("MainViewModel", "IO Error fetching directory listing for $baseUrl", e)
                }
            } catch (e: Exception) {
                Log.e("NFC2Epaper", "Unexpected error parsing directory listing", e)
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "An unexpected error occurred during listing: ${e.localizedMessage}"
                }
            } finally {
                urlConnection?.disconnect()
                withContext(Dispatchers.Main) {
                    _isLoading.value = false
                }
            }
        }
    }

    // Downloads the content of the selected station's latest file
    fun downloadFileContent(stationIdentifier: String) {
        if (baseUrl.isEmpty()) {
            _errorMessage.value = "Base URL is not set. Go to Settings."
            Log.w("MainViewModel", "downloadFileContent: Base URL is empty.")
            return
        }
        if (stationIdentifier.isEmpty()) {
            _selectedStationText.value = ""
            Log.w("MainViewModel", "downloadFileContent: Station identifier is empty.")
            return
        }

        _isLoading.value = true
        _errorMessage.value = "" // Clear previous errors

        viewModelScope.launch(Dispatchers.IO) {
            var urlConnection: HttpURLConnection? = null
            try {
                // Find the exact file path from rawFilePaths that corresponds to the selected station.
                // This logic is crucial: it tries to find the most relevant file by containing the stationIdentifier
                // and preferring files ending with .txt or .config, and tries to get the latest by date if present.

                // Ensure the stationIdentifier itself is not a date or time that might accidentally match.
                val cleanedStationIdentifier = stationIdentifier.split(" ").filter { !it.matches("\\d{4}|\\d{2}|\\d{1,2}".toRegex()) && !it.matches("[0-9]{1,2}:[0-9]{2}\\s[AP]M".toRegex()) }.joinToString(" ").trim()

                val relevantFiles = rawFilePaths.filter { path ->
                    // Check if the path (decoded) contains the station identifier
                    // and also looks like a file (has an extension or matches the date pattern from initial spec)
                    val decodedPath = java.net.URLDecoder.decode(path, "UTF-8")
                    decodedPath.contains(cleanedStationIdentifier, ignoreCase = true) &&
                    (decodedPath.endsWith(".txt", ignoreCase = true) || decodedPath.endsWith(".config", ignoreCase = true) || decodedPath.matches(".*\\d{4}/\\d{2}/\\d{1,2}/?.*".toRegex()))
                }
                Log.d("MainViewModel", "Found ${relevantFiles.size} relevant files for station '$stationIdentifier'. Candidate paths: $relevantFiles")


                // Find the latest file. Assumes paths like /station_name/YYYY/MM/DD.txt or /YYYY/MM/DD.txt
                // Sorting by string usually works for YYYY/MM/DD format if consistent.
                val latestFilePath = relevantFiles.maxByOrNull { path ->
                    val dateRegex = "(\\d{4}/\\d{2}/\\d{1,2})".toRegex() // Look for YYYY/MM/DD pattern
                    dateRegex.find(path)?.value ?: "" // Get the date string for comparison
                } ?: relevantFiles.firstOrNull() // Fallback to first if no date found (e.g., for simple "test.txt")


                if (latestFilePath != null) {
                    // Ensure the full URL is correctly formed. baseUrl already ends with '/'
                    // latestFilePath might be relative, so concatenate carefully.
                    val fullFileUrl = URL(baseUrl + latestFilePath.removePrefix("/"))
                    Log.d("MainViewModel", "Attempting to download content from: $fullFileUrl")

                    urlConnection = fullFileUrl.openConnection() as HttpURLConnection
                    urlConnection.requestMethod = "GET"
                    urlConnection.connect()

                    val responseCode = urlConnection.responseCode
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        val reader = BufferedReader(InputStreamReader(urlConnection.inputStream))
                        val content = reader.readText()
                        reader.close()
                        withContext(Dispatchers.Main) {
                            _selectedStationText.value = content
                        }
                        Log.i("MainViewModel", "Successfully downloaded content for '$stationIdentifier'. Content length: ${content.length}")
                    } else if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                         withContext(Dispatchers.Main) {
                            _errorMessage.value = "File not found for station '$stationIdentifier' at $fullFileUrl. HTTP $responseCode."
                            Log.e("MainViewModel", "File not found: $fullFileUrl. HTTP $responseCode")
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            _errorMessage.value = "Failed to download file: HTTP $responseCode"
                            Log.e("MainViewModel", "HTTP error downloading file content: $responseCode from $fullFileUrl")
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        _selectedStationText.value = ""
                        _errorMessage.value = "No relevant file path found in listing for station: $stationIdentifier. Check file naming on server."
                        Log.w("MainViewModel", "No relevant file path found in rawFilePaths for station: $stationIdentifier")
                    }
                }
            } catch (e: MalformedURLException) {
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "Invalid URL format for file download: ${e.localizedMessage}"
                    Log.e("MainViewModel", "Malformed URL for file download: ${e.localizedMessage}", e)
                }
            } catch (e: FileNotFoundException) {
                 withContext(Dispatchers.Main) {
                    _errorMessage.value = "File not found at specified path: ${e.localizedMessage}"
                    Log.e("MainViewModel", "File not found during download: ${e.localizedMessage}", e)
                }
            }
            catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "Network error downloading content: ${e.localizedMessage}"
                    Log.e("MainViewModel", "IO Error downloading content: ${e.localizedMessage}", e)
                }
            } catch (e: Exception) {
                Log.e("NFC2Epaper", "Unexpected error downloading file content", e)
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "An unexpected error occurred during download: ${e.localizedMessage}"
                }
            } finally {
                urlConnection?.disconnect()
                withContext(Dispatchers.Main) {
                    _isLoading.value = false
                }
            }
        }
    }
}
