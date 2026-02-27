import { Link } from 'react-router-dom';
import { useAuth } from '../AuthContext';

export default function Home() {
  const { isAuthenticated, role } = useAuth();

  return (
    <div className="min-h-[80vh] flex items-center justify-center">
      <div className="text-center max-w-2xl mx-auto px-4">
        <h1 className="text-5xl font-bold text-gray-800 mb-4">
          💈 Scheduler
        </h1>
        <p className="text-xl text-gray-500 mb-8">
          System rezerwacji wizyt dla salonów kosmetycznych i fryzjerskich
        </p>

        <div className="space-y-6">
          {/* Main CTA — always visible */}
          <div className="flex flex-col sm:flex-row gap-4 justify-center">
            <Link
              to="/book"
              className="bg-indigo-600 text-white px-8 py-3 rounded-xl hover:bg-indigo-700 transition font-semibold text-lg"
            >
              📅 Umów wizytę
            </Link>

            {isAuthenticated && role === 'customer' && (
              <Link
                to="/my-reservations"
                className="bg-white text-indigo-600 border-2 border-indigo-600 px-8 py-3 rounded-xl hover:bg-indigo-50 transition font-semibold text-lg"
              >
                📋 Moje wizyty
              </Link>
            )}

            {isAuthenticated && role === 'staff' && (
              <>
                <Link
                  to="/dashboard"
                  className="bg-white text-indigo-600 border-2 border-indigo-600 px-8 py-3 rounded-xl hover:bg-indigo-50 transition font-semibold text-lg"
                >
                  🏠 Panel
                </Link>
                <Link
                  to="/schedule"
                  className="bg-white text-indigo-600 border-2 border-indigo-600 px-8 py-3 rounded-xl hover:bg-indigo-50 transition font-semibold text-lg"
                >
                  📅 Harmonogram
                </Link>
              </>
            )}
          </div>

          {!isAuthenticated && (
            <p className="text-sm text-gray-400">
              Przeglądaj usługi i terminy bez logowania!<br/>
              Logowanie wymagane dopiero przy potwierdzeniu rezerwacji.
            </p>
          )}
        </div>
      </div>
    </div>
  );
}
