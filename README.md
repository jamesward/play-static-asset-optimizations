# Optimizing Static Asset Loading with Play Framework

Modern web applications are a mix of a back-end services, dynamic web pages, custom static assets, and library-based static assets.  To maintain a productive development process it is easiest to have all this stuff in one project.  But in production there are a number of optimizations that can dramatically speed up the loading of the application.

HTTP 304, Not Modified, responses enable the browser to not re-download an entire static asset a second time.  Far-future expires enable the browser to cache static assets for a very long time so that they never request them a second time.  The challenge with far-future expires is that you need to have a way to invalidate that cache.  Asset fingerprinting allows you to do that invalidation.  GZip encoding compresses the static assets.  Putting static assets on a CDN caches the static assets near the consumer of the content.

Lets look at how to setup these different optimizations with a Play Framework application.


## Not Modified Responses

In Play you don't need to do anything special to enable 304, Not Modified responses for static content.  The `Assets` controller does it for you automatically.  Lets take a look at how this works...  If you have a file in a Play app named `app/assets/javascripts/index.js` containing:

    alert("hello");

Lets make a request to Play for this file: `curl -v http://localhost:9000/assets/javascripts/index.js`

    > GET /assets/javascripts/index.js HTTP/1.1
    > Host: localhost:9000
    > 
    < HTTP/1.1 200 OK
    < Last-Modified: Sat, 26 Apr 2014 15:23:40 GMT
    < Etag: "1c5e355bf7bd8c9373a92714fece94de5ca13362"
    < Content-Length: 14
    < Content-Type: application/javascript; charset=utf-8
    < Date: Sat, 26 Apr 2014 15:23:40 GMT
    < 
    alert("asdf");

The response is a static code 200 and contains the contents of the `index.js` file.  The HTTP response contains `Last-Modified` and `Etag` headers.  These provide two different ways of handing 304 responses.  As you guessed the `Last-Modified` tells the client / browser when the content was last modified.  (The `Date` header is included so that the client can know what time the server thinks it is and is required to effectively use `Last-Modified` based 304's.)  The `Etag` specifies a UUID for the content which the client can use to determine if it has the right version of some content.

To see 304's in action we need to use either the `Last-Modified` or `Etag` for a subsequent request to the server.  To use the `Last-Modified` method an `If-Modified-Since` request header is sent with the `Last-Modified` date of the content we already have cached locally.  For instance we can test this with something like:

    curl -v -H "If-Modified-Since: Sat, 26 Apr 2014 15:23:40 GMT" http://localhost:9000/assets/javascripts/index.js

The server will check that I have the latest content and if so respond with the 304:

    > GET /assets/javascripts/index.js HTTP/1.1
    > User-Agent: curl/7.35.0
    > If-Modified-Since: Sat, 26 Apr 2014 15:23:40 GMT
    > 
    < HTTP/1.1 304 Not Modified
    < Date: Sat, 26 Apr 2014 15:39:29 GMT
    < Content-Length: 0

For the `Etag` method an `If-None-Match` request header is sent containing the `Etag` value of the content we already have cached locally.  For example:

    curl -v -H "If-None-Match: \"1c5e355bf7bd8c9373a92714fece94de5ca13362\"" http://localhost:9000/assets/javascripts/index.js

Again the server will return a 304 if what I have locally matches what the server has. 

    > GET /assets/javascripts/index.js HTTP/1.1
    > Host: localhost:9000
    > If-None-Match: "1c5e355bf7bd8c9373a92714fece94de5ca13362"
    > 
    < HTTP/1.1 304 Not Modified
    < Etag: "1c5e355bf7bd8c9373a92714fece94de5ca13362"
    < Last-Modified: Sat, 26 Apr 2014 15:23:40 GMT
    < Content-Length: 0

The 304 responses can really speed up web apps but things aren't totally optimized yet since a round trip to the server is still needed to validate the local cache.


## Far-Future Expires

Browsers can avoid a network round trip by caching assets based on a `Cache-Control` HTTP response header.  This header indicates how long the browser should be able to rely on it's cached version.  When running Play in dev mode the default `Cache-Control` for static assets is `no-cache` which tells the browser not to use the cached version.  (This doesn't mean the browser can't still use the `Etag` and `Last-Modified` methods - but those require a round trip.)

If you make a request to a static asset named `app/assets/javascripts/index.js` using `curl -v http://localhost:9000/assets/javascripts/index.js` you should see output including:

    < Cache-Control: no-cache

This happens in Play's dev mode because when you are testing constantly changing static assets you do not want the browser caching them.  If you restart your Play app in Prod mode `play start` or `activator start` and make the same request you should see:

    < Cache-Control: max-age=3600

This sets the default cache length to one hour, meaning the browser will use it's cached version of this content for an hour.  After that another server request will be needed.  You can override the default one hour with a new value by setting a `assets.defaultCache` configuration parameter in your `conf/application.conf` file.  For instance:

    assets.defaultCache="max-age=1234"

The only way to get the browser to manually get the browser to invalidate its cache before the set time is to get the asset from a different URL.  If you can do that, or if you know an asset will never change, then you can use *Far-Future Expires* which simply use a very large value in the `Cache-Control` header, for instance:

    assets.defaultCache="max-age=290304000"

This would instruct the browser to not request that asset for another 3,360 days.


## Asset Fingerprinting

Asset fingerprinting is a method to put version information in the URL so that you can invalidate the browser's cache by pointing to a new URL.  Play doesn't yet have a built-in way to do this (but will in 2.3) so we need to handle fingerprinting manually.  There are a number of ways to do this: sbt plugins, S3 content upload scripts, and an Asset controller wrapper.  The method I like most (until 2.3 arrives) is an Asset controller wrapper because it is pretty simple and doesn't require an additional deployment / build step.  To setup this method of fingerprinting create a new controller in `app/controllers/StaticAssets.scala` containing:

    package controllers
    
    import play.api.mvc.{Action, Controller}
    import play.api.Play.current
    
    
    object StaticAssets extends Controller {
      
      // drop the version
      def at(path: String, version: String, file: String): Action[_] = {
        Assets.at(path, file)
      }
    
      // returns a path that has a version if the assets.version config is set
      def url(file: String): String = {
        val maybeAssetsVersion = current.configuration.getString("assets.version")
        maybeAssetsVersion.fold(routes.Assets.at(file).url)(routes.StaticAssets.at(_, file).url)
      }
      
    }

This new controller wraps Play's `Assets` controller and adds fingerprinting based on a configured version.  The `at` function just returns a static asset using the regular `Assets.at` method.  The `url` function looks to see if a `assets.version` config param exists and if so it returns a String URL containing that version number, otherwise it returns the non-versioned URL.  This new controller needs a new route definition in the `conf/routes` file:

    GET     /assets-static/:version/*file  controllers.StaticAssets.at(path="/public", version, file)

Then when doing reverse routing for assets use the new `StaticAssets.url` method, like:

    <script type='text/javascript' src='@StaticAssets.url("javascripts/index.js")'></script>

Setting the `assets.version` config parameter in `conf/application.conf` will change the static asset URLs to the fingerprinted URLs.  For instance, before setting that param, a call to `StaticAssets.url("javascripts/index.js")` results in `/assets/javascripts/index.js` and if you add `assets.version=1` to `conf/application.conf` then the result is `/assets-static/1/javascripts/index.js` - the fingerprinted URL.  Deploying a new version of the app with a new `assets.version` config param results in new URLs, thus invalidating any Far-Future Expires and 304-based caching.


## GZip Encoding

GZip encoding is very widely supported by browsers but it is not turned on by default in Play.  However, enabling it is very easy.  This will result in significantly smaller HTTP responses for static content.  First add the `filters` library to a project by updating the `build.sbt` file to something like:

    libraryDependencies ++= Seq(
      // your deps here
      filters,
      "org.webjars" %% "webjars-play" % "2.2.1-2",
      "org.webjars" % "bootstrap" % "3.1.1"
    )

Then create a new `app/Global.scala` file containing:

    import play.api.mvc.WithFilters
    import play.filters.gzip.GzipFilter
    
    object Global extends WithFilters(new GzipFilter())

That is it!  This even is useful when working with minified JavaScript and CSS.  For instance, without GZip, the `jquery.min.js` file from jQuery 1.9.0 is 91.1KB.  With GZip enabled the HTTP response goes down to 32.5KB!


## CDN

Another optimization you can make with static content in Play is to not serve the it from Play.  Play is not really tuned for serving static content and usually a web app's servers are centrally located.  Using a Content Delivery Network (CDN) or caching proxy can really speed up the loading of static content for most users.  A caching proxy like Nginx (with additional plugins) can move the bulk of content loading to something that is specifically tuned for serving content.  A CDN takes that a step further is to then serve that content from a location that is geographically near the consumer.  Surprisingly the speed of light is pretty slow when it comes to moving content around the globe.  A round trip TCP packet between San Francisco and New York can take almost 100ms in just transit time.  These 100ms round trips can really add up.  So caching static content geographically close to the consumer is usually an important and trivial web app optimization.

There are now a number of CDNs which make it very easy to "edge-cache" content, like: [CloudFront](https://aws.amazon.com/cloudfront/), [Fastly](http://www.fastly.com/), and [MaxCDN](http://www.maxcdn.com/).  The original CDNs like Akamai would require an additional upload step to distribute the content.  Luckily modern CDNs now support a proxy mode where no additional steps are required to distribute content on the CDN.

If you setup a proxy CDN then the CDN doesn't have your content until someone requests it.  So if a user requests `http://cdn.foo.com/foo.js` (which points to a caching CDN) and the CDN doesn't have the requested content then it will make a request to the configured `origin` server to get the content.  Subsequent requests will just get the content from the CDN.  And that content will be served from the closest place possible to the user, thus reducing the transit time.  Most modern CDNs also support all of the optimizations covered above.  So your HTTP response headers and content not only impact how the browser caches your content, they can also impact how the CDN caches your content.

Your Play app needs to be reachable by the CDN for this all to work so if you want to follow along then you will need to deploy your app on a publicly accessible service like Heroku.  Once your app is publicly accessible you can follow the usual steps to create a CDN in front of it.  For CloudFront it is pretty easy to create a new *Distribution* in the [AWS Management Console](https://console.aws.amazon.com/cloudfront/home).  Just make sure you specify the *Origin Domain Name* to be the domain where the Play app is deployed.

Once you have an origin-based CDN setup the final step is to get the Play app to use the new CDN URLs instead of the regular relative URLs.  In local development mode we want to still use the local Play app while in production we want to use the CDN.  Building on the custom controller in the fingerprinting section we need to add in the capability to prepend a CDN prefix in front of the static asset URLs.  (Note: The actual serving of assets doesn't change and this can be used without fingerprinting.)  Here is a new `app/controllers.StaticAssets.scala` controller that has both the fingerprinting and the CDN support

    package controllers
    
    import play.api.mvc.{Action, Controller}
    import play.api.Play.current
    
    
    object StaticAssets extends Controller {
      
      // drop the version and serve the asset
      def at(path: String, version: String, file: String): Action[_] = {
        Assets.at(path, file)
      }
    
      // returns a path that has a version if the assets.version config is set
      // prepends a url if the assets.url config is set
      def url(file: String): String = {
        val maybeAssetsVersion = current.configuration.getString("assets.version")
        val maybeVersionedUrl = maybeAssetsVersion.fold(routes.Assets.at(file).url)(routes.StaticAssets.at(_, file).url)
    
        val maybeAssetsUrl = current.configuration.getString("assets.url")
        maybeAssetsUrl.fold(maybeVersionedUrl)(_ + maybeVersionedUrl)
      }
      
    }

Once the possibly versioned URL is determined in the `url` function, if the `assets.url` config exists then it's value will be prepended in front of the `maybeVersionedUrl` value.  Try it by adding the following to the `conf/application.conf` file:

    assets.url="http://foo.com"

Now the calls to `StaticAssets.url` will return a fully qualified (and possibly versioned) URL.  If you setup a proxy CDN then you should be able to use its URL.


## Wrap Up

Each of these methods of static asset optimization provide a great way to speed up the loading of your Play web application.  They can be used together or independently.  They can also be used with WebJars.

Check out the a working sample app that includes these optimizations on GitHub: [https://github.com/jamesward/play-static-asset-optimizations](https://github.com/jamesward/play-static-asset-optimizations)