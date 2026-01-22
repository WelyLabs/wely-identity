# Keycloak Custom Theme - Calendar App

## Installation Instructions

### 1. Copy Theme to Keycloak

Copy the entire `calendar-app` folder to your Keycloak themes directory:

```bash
# For Keycloak running in Docker
docker cp keycloak-theme/calendar-app <container-name>:/opt/keycloak/themes/

# For standalone Keycloak installation
cp -r keycloak-theme/calendar-app /path/to/keycloak/themes/
```

### 2. Configure Realm to Use Theme

1. Open Keycloak Admin Console (usually http://localhost:8080)
2. Select your realm (`calendar-app`)
3. Go to **Realm Settings** → **Themes** tab
4. Set **Login Theme** to `calendar-app`
5. Click **Save**

### 3. Clear Browser Cache

Clear your browser cache or open an incognito window to see the new theme.

## Theme Features

✅ **Gradient Background**: Blue to orange gradient matching the app
✅ **Glassmorphism**: Translucent card with backdrop blur
✅ **Modern Design**: Clean, rounded corners, smooth transitions
✅ **Responsive**: Works on mobile and desktop
✅ **Accessible**: Proper focus states and color contrast

## Customization

To customize colors, edit `keycloak-theme/calendar-app/login/resources/css/login.css`:

- **Primary Gradient**: Lines 7-8 (background)
- **Button Gradient**: Lines 110-111
- **Border Colors**: Search for `#e2e8f0`
- **Text Colors**: Search for `#1e293b`, `#64748b`

## Testing

1. Start Keycloak server
2. Navigate to your app's landing page
3. Click "Get Started Free"
4. You should see the custom login page with gradient background

## Troubleshooting

**Theme not showing?**
- Verify theme folder is in correct location
- Check Keycloak logs for theme errors
- Clear browser cache
- Restart Keycloak server

**Styles not applying?**
- Check `theme.properties` file exists
- Verify CSS file path in theme.properties
- Check browser console for 404 errors
