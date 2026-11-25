package com.apexhorizon.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.apexhorizon.app.BuildConfig
import com.apexhorizon.app.R
import com.apexhorizon.app.ui.AppViewModel
import com.apexhorizon.app.ui.theme.Apex

@Composable
fun AboutScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val policyUrl = stringResource(R.string.privacy_policy_url)
    ScreenScaffold(title = stringResource(R.string.about_title), onBack = { vm.back() }) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(painterResource(R.drawable.logo_mark), contentDescription = stringResource(R.string.logo_description), modifier = Modifier.size(88.dp))
            Text("${stringResource(R.string.app_name)} ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium, color = Apex.TextPrimary)

            ApexCard {
                Text(stringResource(R.string.about_how_title), style = MaterialTheme.typography.titleMedium, color = Apex.TextPrimary)
                Text(stringResource(R.string.about_how_body), style = MaterialTheme.typography.bodyMedium, color = Apex.TextDim)
            }
            ApexCard {
                Text(stringResource(R.string.about_safety_title), style = MaterialTheme.typography.titleMedium, color = Apex.NeonOrange)
                Text(stringResource(R.string.disclaimer_body), style = MaterialTheme.typography.bodyMedium, color = Apex.TextDim)
            }
            ApexCard {
                Text(stringResource(R.string.about_privacy_title), style = MaterialTheme.typography.titleMedium, color = Apex.TextPrimary)
                Text(stringResource(R.string.about_privacy_body), style = MaterialTheme.typography.bodyMedium, color = Apex.TextDim)
            }
            ApexCard {
                Text(stringResource(R.string.about_permissions_title), style = MaterialTheme.typography.titleMedium, color = Apex.TextPrimary)
                Text(stringResource(R.string.about_permissions_body), style = MaterialTheme.typography.bodyMedium, color = Apex.TextDim)
            }
            if (!policyUrl.contains("YOUR-")) {
                SecondaryButton(stringResource(R.string.action_privacy_policy), onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(policyUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: ActivityNotFoundException) {
                    }
                })
            }
        }
    }
}
