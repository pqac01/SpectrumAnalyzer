package com.example.spectrumanalyzer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.navigation.findNavController
import com.example.spectrumanalyzer.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 0)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main)
                val currentFragment = navHostFragment?.childFragmentManager?.fragments?.get(0) as? SpectrogramFragment
                currentFragment?.showSettings()
                true
            }
            R.id.action_recordings -> {
                val navController = findNavController(R.id.nav_host_fragment_content_main)
                if (navController.currentDestination?.id != R.id.SecondFragment) {
                    navController.navigate(R.id.action_FirstFragment_to_SecondFragment)
                }
                true
            }
            R.id.action_sonar -> {
                startActivity(Intent(this, SonarActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}