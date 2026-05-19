package co.sequred.identity.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import co.sequred.identity.R

/**
 * The wordmark + tagline used on lock/setup screens. Keeps the lockup
 * consistent — the inline copies in each screen would drift as the design
 * evolves.
 */
@Composable
fun SeQuredHeader(
    modifier: Modifier = Modifier,
    tagline: String? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Image(
            painter = painterResource(R.drawable.sequred_logo),
            contentDescription = "SeQured",
            modifier = Modifier.height(56.dp),
        )
        if (tagline != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = tagline,
                style = BrandType.body(13),
                color = Brand.TextSecondary,
            )
        }
    }
}
